/**
Open Bank Project - API
Copyright (C) 2011-2019, TESOBE GmbH.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Email: contact@tesobe.com
TESOBE GmbH.
Osloer Strasse 16/17
Berlin 13359, Germany

This product includes software developed at
TESOBE (http://www.tesobe.com/)

  */
package code.model.dataAccess

import java.util.UUID.randomUUID

import code.api.util.CommonFunctions.validUri
import code.UserRefreshes.UserRefreshes
import code.accountholders.AccountHolders
import code.api.cache.Caching
import code.api.dynamic.endpoint.helper.DynamicEndpointHelper
import code.api.util.APIUtil._
import code.api.util.ErrorMessages._
import code.api.util._
import code.api.{APIFailure, Constant, DirectLogin, GatewayLogin, OAuthHandshake}
import code.bankconnectors.Connector
import code.context.UserAuthContextProvider
import code.entitlement.Entitlement
import code.loginattempts.LoginAttempt
import code.snippet.WebUI
import code.token.TokensOpenIDConnect
import code.users.{UserAgreementProvider, Users}
import code.util.Helper
import code.util.Helper.{MdcLoggable, ObpS}
import code.views.Views
import com.openbankproject.commons.model._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.mapper._
import net.liftweb.util.Mailer.{BCC, From, Subject, To}
import net.liftweb.util._

import scala.collection.immutable.List
import scala.xml.{Elem, NodeSeq, Text}
import com.openbankproject.commons.ExecutionContext.Implicits.global
import code.webuiprops.MappedWebUiPropsProvider.getWebUiPropsValue
import org.apache.commons.lang3.StringUtils
import code.util.HydraUtil._
import com.github.dwickern.macros.NameOf.nameOf
import com.tesobe.CacheKeyFromArguments
import sh.ory.hydra.model.AcceptLoginRequest
import net.liftweb.http.S.fmapFunc
import net.liftweb.sitemap.Loc.{If, LocParam, Template}
import sh.ory.hydra.api.AdminApi
import net.liftweb.sitemap.Loc.strToFailMsg

import scala.concurrent.Future

/**
 * An O-R mapped "User" class that includes first name, last name, password
  *
  * 1 AuthUser : is used for authentication, only for webpage Login in stuff
  *   1) It is MegaProtoUser, has lots of methods for validation username, password, email ....
  *      Such as lost password, reset password ..... 
  *      Lift have some helper methods to make these things easily. 
  *   
  *  
  * 
  * 2 ResourceUser: is only a normal LongKeyedMapper 
  *   1) All the accounts, transactions ,roles, views, accountHolders, customers... should be linked to ResourceUser.userId_ field.
  *   2) The consumer keys, tokens are also belong ResourceUser
  *  
  * 
  * 3 RelationShips:
  *   1)When `Sign up` new user --> create AuthUser --> call AuthUser.save --> create ResourceUser user.
  *      They share the same username and email.
  *   2)AuthUser `user` field as the Foreign Key to link to Resource User. 
  *      one AuthUser <---> one ResourceUser 
  *
 */
class AuthUser extends MegaProtoUser[AuthUser] with CreatedUpdated with MdcLoggable {
  def getSingleton = AuthUser // what's the "meta" server

  object user extends MappedLongForeignKey(this, ResourceUser)

  override lazy val firstName = new MyFirstName
  
  protected class MyFirstName extends MappedString(this, 100) {
    def isEmpty(msg: => String)(value: String): List[FieldError] =
      value match {
        case null                  => List(FieldError(this, Text(msg))) // issue 179
        case e if e.trim.isEmpty   => List(FieldError(this, Text(msg))) // issue 179
        case _                     => Nil
      }
    
    override def displayName = fieldOwner.firstNameDisplayName
    override val fieldId = Some(Text("txtFirstName"))
    override def validations = isEmpty(Helper.i18n("Please.enter.your.first.name")) _ :: super.validations

    override def _toForm: Box[Elem] =
      fmapFunc({s: List[String] => this.setFromAny(s)}){name =>
        Full(appendFieldId(<input type={formInputType} 
                                  maxlength={maxLen.toString}
                                  aria-labelledby={displayName} 
                                  aria-describedby={uniqueFieldId.getOrElse("")}
                                  name={name}
                                  value={get match {case null => "" case s => s.toString}}/>))
      }
  }
  
  override lazy val lastName = new MyLastName

  protected class MyLastName extends MappedString(this, 100) {
    def isEmpty(msg: => String)(value: String): List[FieldError] =
      value match {
        case null                  => List(FieldError(this, Text(msg))) // issue 179
        case e if e.trim.isEmpty   => List(FieldError(this, Text(msg))) // issue 179
        case _                     => Nil
      }

    override def displayName = fieldOwner.lastNameDisplayName
    override val fieldId = Some(Text("txtLastName"))
    override def validations = isEmpty(Helper.i18n("Please.enter.your.last.name")) _ :: super.validations

    override def _toForm: Box[Elem] =
      fmapFunc({s: List[String] => this.setFromAny(s)}){name =>
        Full(appendFieldId(<input type={formInputType}
                                  maxlength={maxLen.toString}
                                  aria-labelledby={displayName}
                                  aria-describedby={uniqueFieldId.getOrElse("")}
                                  name={name}
                                  value={get match {case null => "" case s => s.toString}}/>))
      }
    
  }
  
  /**
   * Username is a valid email address or the regex below:
   * Regex to validate a username
   * 
   * ^(?=.{8,100}$)(?![_.])(?!.*[_.]{2})[a-zA-Z0-9._]+(?<![_.])$
   * └─────┬────┘└───┬──┘└─────┬─────┘└─────┬─────┘ └───┬───┘
   *       │         │         │            │           no _ or . at the end
   *       │         │         │            │
   *       │         │         │            allowed characters
   *       │         │         │
   *       │         │         no __ or _. or ._ or .. inside
   *       │         │
   *       │         no _ or . at the beginning
   *       │
   *       username is 8-100 characters long
   *       
   */
  private val usernameRegex = """^(?=.{8,100}$)(?![_.])(?!.*[_.]{2})[a-zA-Z0-9._]+(?<![_.])$""".r

  /**
    * The username field for the User.
    */
  lazy val username: userName = new userName()
  class userName extends MappedString(this, 100) {
    def isEmpty(msg: => String)(value: String): List[FieldError] =
      value match {
        case null                  => List(FieldError(this, Text(msg))) // issue 179
        case e if e.trim.isEmpty   => List(FieldError(this, Text(msg))) // issue 179
        case _                     => Nil
      }
    def usernameIsValid(msg: => String)(e: String) = e match {
      case null                                             => List(FieldError(this, Text(msg)))
      case e if e.trim.isEmpty                              => List(FieldError(this, Text(msg)))
      case e if emailRegex.findFirstMatchIn(e).isDefined    => Nil // Email is valid username
      case e if usernameRegex.findFirstMatchIn(e).isDefined => Nil
      case _                                                => List(FieldError(this, Text(msg)))
    }
    override def displayName = S.?("Username")
    @deprecated("Use UniqueIndex(username, provider)","27 December 2021")
    override def dbIndexed_? = false // We use more general index UniqueIndex(username, provider) :: super.dbIndexes
    override def validations = isEmpty(Helper.i18n("Please.enter.your.username")) _ ::
                               usernameIsValid(Helper.i18n("invalid.username")) _ ::
                               valUnique(Helper.i18n("unique.username")) _ ::
                               valUniqueExternally(Helper.i18n("unique.username")) _ :: 
                               super.validations
    override val fieldId = Some(Text("txtUsername"))

    override def _toForm: Box[Elem] =
      fmapFunc({s: List[String] => this.setFromAny(s)}){name =>
        Full(appendFieldId(<input type={formInputType}
                                  maxlength={maxLen.toString}
                                  aria-labelledby={displayName}
                                  aria-describedby={uniqueFieldId.getOrElse("")}
                                  name={name}
                                  value={get match {case null => "" case s => s.toString}}/>))
      }
    
    /**
     * Make sure that the field is unique in the CBS
     */
    def valUniqueExternally(msg: => String)(uniqueUsername: String): List[FieldError] ={
      if (APIUtil.getPropsAsBoolValue("connector.user.authentication", false)) {
        Connector.connector.vend.checkExternalUserExists(uniqueUsername, None).map(_.sub) match {
          case Full(returnedUsername) => // Get the username via connector
            if(uniqueUsername == returnedUsername) { // Username is NOT unique
              List(FieldError(this, Text(msg))) // provide the error message
            } else { 
              Nil // All good. Allow username creation
            }
          case ParamFailure(message,_,_,APIFailure(errorMessage, errorCode)) if errorMessage.contains("NO DATA") => // Cannot get the username via connector
            Nil // All good. Allow username creation
          case _ => // Any other case we provide error message
            List(FieldError(this, Text(msg)))
        }
      } else {
        Nil // All good. Allow username creation
      }
    }
      
      
  }

  override lazy val password = new MyPasswordNew
  
  lazy val signupPasswordRepeatText = getWebUiPropsValue("webui_signup_body_password_repeat_text", S.?("repeat"))
 
  class MyPasswordNew extends MappedPassword(this) {
    lazy val preFilledPassword = if (APIUtil.getPropsAsBoolValue("allow_pre_filled_password", true)) {get.toString} else ""
    override def _toForm: Box[NodeSeq] = {
      S.fmapFunc({s: List[String] => this.setFromAny(s)}){funcName =>
        Full(
          <span>
            {appendFieldId(<input id="textPassword" aria-labelledby="Password" aria-describedby={uniqueFieldId.getOrElse("")} type={formInputType} name={funcName} value={preFilledPassword}/> ) }
            <div id="signup-error" class="alert alert-danger hide">
              <span data-lift={s"Msg?id=${uniqueFieldId.getOrElse("")}&errorClass=error"}/>
            </div>
            <div id ="repeat-password">{signupPasswordRepeatText}</div>
            <input id="textPasswordRepeat" aria-labelledby="Password Repeat" aria-describedby={uniqueFieldId.getOrElse("")}  type={formInputType} name={funcName} value={preFilledPassword}/>
            <div id="signup-error" class="alert alert-danger hide">
              <span data-lift={s"Msg?id=${uniqueFieldId.getOrElse("")}_repeat&errorClass=error"}/>
            </div>
        </span>)
      }
    }
    
    override def displayName = fieldOwner.passwordDisplayName
    
    private var passwordValue = ""
    private var invalidPw = false
    private var invalidMsg = ""

    // TODO Remove double negative and abreviation.
    // TODO  “invalidPw” = false -> “strongPassword = true” etc.
    override def setFromAny(f: Any): String = {
      def checkPassword() = {
        def isPasswordEmpty() = {
          if (passwordValue.isEmpty())
            true
          else {
            passwordValue match {
              case "*" | null | MappedPassword.blankPw =>
                true
              case _ =>
                false
            }
          }
        }
        isPasswordEmpty() match {
          case true =>
            invalidPw = true;
            invalidMsg = Helper.i18n("please.enter.your.password")
            S.error("authuser_password_repeat", Text(Helper.i18n("please.re-enter.your.password")))
          case false =>
            if (fullPasswordValidation(passwordValue))
              invalidPw = false
            else {
              invalidPw = true
              invalidMsg = S.?(ErrorMessages.InvalidStrongPasswordFormat.split(':')(1))
              S.error("authuser_password_repeat", Text(invalidMsg))
            }
        }
      }
      f match {
        case a: Array[String] if (a.length == 2 && a(0) == a(1)) => {
          passwordValue = a(0).toString
          checkPassword()
          this.set(a(0))
        }
        case l: List[_] if (l.length == 2 && l.head.asInstanceOf[String] == l(1).asInstanceOf[String]) => {
          passwordValue = l(0).asInstanceOf[String]
          checkPassword()
          this.set(l.head.asInstanceOf[String])
        }
        case _ => {
          invalidPw = true;
          invalidMsg = Helper.i18n("passwords.do.not.match")
          S.error("authuser_password_repeat", Text(invalidMsg))
        }
      }
      get
    }
    
    override def validate: List[FieldError] = {
      if (!invalidPw && password.get != "*") super.validate
      else if (invalidPw) List(FieldError(this, Text(invalidMsg))) ++ super.validate
      else List(FieldError(this, Text(Helper.i18n("please.enter.your.password")))) ++ super.validate
    }
    
  }

  /**
   * The provider field for the User.
   */
  lazy val provider: userProvider = new userProvider()
  class userProvider extends MappedString(this, 100) {
    override def displayName = S.?("provider")
    override val fieldId = Some(Text("txtProvider"))
    override def validations = validUri(this) _ :: super.validations
    override def defaultValue: String = Constant.localIdentityProvider
  }


  def getProvider() = {
    if(provider.get == null || provider.get == "") {
      Constant.localIdentityProvider
    } else {
      provider.get
    }
  }

  def createUnsavedResourceUser() : ResourceUser = {
    val user = Users.users.vend.createUnsavedResourceUser(getProvider(), Some(username.get), Some(username.get), Some(email.get), None).openOrThrowException(attemptedToOpenAnEmptyBox)
    user
  }

  def getResourceUsersByEmail(userEmail: String) : List[ResourceUser] = {
    Users.users.vend.getUserByEmail(userEmail) match {
      case Full(userList) => userList
      case _ => List()
    }
  }

  def getResourceUserByProviderAndUsername(provider: String, username: String) : Box[User] = {
    Users.users.vend.getUserByProviderAndUsername(provider, username)
  }

  override def save(): Boolean = {
    if(! (user defined_?)){
      logger.info("user reference is null. We will create a ResourceUser")
      val resourceUser = createUnsavedResourceUser()
      val savedUser = Users.users.vend.saveResourceUser(resourceUser)
      user(savedUser)   //is this saving resourceUser into a user field?
    }
    else {
      logger.info("user reference is not null. Trying to update the ResourceUser")
      Users.users.vend.getResourceUserByResourceUserId(user.get).map{ u =>{
          logger.info("API User found ")
          u.name_(username.get)
          .email(email.get)
          .providerId(username.get)
          .save
        }
      }
    }
    super.save
  }

  override def delete_!(): Boolean = {
    user.obj.map(u => Users.users.vend.deleteResourceUser(u.id.get))
    super.delete_!
  }

  // Regex to validate an email address as per W3C recommendations: https://www.w3.org/TR/html5/forms.html#valid-e-mail-address
  private val emailRegex = """^[a-zA-Z0-9\.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r

  def isEmailValid(e: String): Boolean = e match{
    case null                                           => false
    case e if e.trim.isEmpty                            => false
    case e if emailRegex.findFirstMatchIn(e).isDefined  => true
    case _                                              => false
  }

  // Override the validate method of MappedEmail class
  // There's no way to override the default emailPattern from MappedEmail object
  override lazy val email = new MyEmail(this, 48) {
    override def validations = super.validations
    override def dbIndexed_? = false
    override def validate = i_is_! match {
      case null                  => List(FieldError(this, Text(Helper.i18n("Please.enter.your.email"))))
      case e if e.trim.isEmpty   => List(FieldError(this, Text(Helper.i18n("Please.enter.your.email"))))
      case e if (!isEmailValid(e))  => List(FieldError(this, Text(S.?("invalid.email.address"))))
      case _                     => Nil
    }
    override def _toForm: Box[Elem] =
      fmapFunc({s: List[String] => this.setFromAny(s)}){name =>
        Full(appendFieldId(<input type={formInputType}
                                  maxlength={maxLen.toString}
                                  aria-labelledby={displayName}
                                  aria-describedby={uniqueFieldId.getOrElse("")}
                                  name={name}
                                  value={get match {case null => "" case s => s.toString}}/>))
      }
  }
}

/**
 * The singleton that has methods for accessing the database
 */
object AuthUser extends AuthUser with MetaMegaProtoUser[AuthUser]{
import net.liftweb.util.Helpers._

  /**Marking the locked state to show different error message */
  val usernameLockedStateCode = Long.MaxValue

  val connector = APIUtil.getPropsValue("connector").openOrThrowException("no connector set")
  val starConnectorSupportedTypes = APIUtil.getPropsValue("starConnector_supported_types","")

  override def dbIndexes: List[BaseIndex[AuthUser]] = UniqueIndex(username, provider) ::super.dbIndexes
  
  override def emailFrom = APIUtil.getPropsValue("mail.users.userinfo.sender.address", "sender-not-set")

  override def screenWrap = Full(<lift:surround with="default" at="content"><lift:bind /></lift:surround>)
  // define the order fields will appear in forms and output
  override def fieldOrder = List(id, firstName, lastName, email, username, password, provider)
  override def signupFields = List(firstName, lastName, email, username, password)

  // To force validation of email addresses set this to false (default as of 29 June 2021)
  override def skipEmailValidation = APIUtil.getPropsAsBoolValue("authUser.skipEmailValidation", false)

  override def loginXhtml = {
    val loginXml = Templates(List("templates-hidden","_login")).map({
        "form [action]" #> {ObpS.uri} &
        "#loginText * " #> {S.?("log.in")} &
        "#usernameText * " #> {S.?("username")} &
        "#passwordText * " #> {S.?("password")} &
        "#login_challenge [value]" #> ObpS.param("login_challenge").getOrElse("") &
        "autocomplete=off [autocomplete] " #> APIUtil.getAutocompleteValue &
        "#recoverPasswordLink * " #> {
          "a [href]" #> {lostPasswordPath.mkString("/", "/", "")} &
          "a *" #> {S.?("recover.password")}
        } &
        "#SignUpLink * " #> {
          "a [href]" #> {AuthUser.signUpPath.foldLeft("")(_ + "/" + _)} &
          "a *" #> {S.?("sign.up")}
        }
      })

    <div>{loginXml getOrElse NodeSeq.Empty}</div>
  }


  // Update ResourceUser.LastUsedLocale only once per session in 60 seconds
  def updateComputedLocale(sessionId: String, computedLocale: String): Boolean = {
    /**
     * Please note that "var cacheKey = (randomUUID().toString, randomUUID().toString, randomUUID().toString)"
     * is just a temporary value field with UUID values in order to prevent any ambiguity.
     * The real value will be assigned by Macro during compile time at this line of a code:
     * https://github.com/OpenBankProject/scala-macros/blob/master/macros/src/main/scala/com/tesobe/CacheKeyFromArgumentsMacro.scala#L49
     */
    import scala.concurrent.duration._
    val ttl: Duration = FiniteDuration(60, "second")
    var cacheKey = (randomUUID().toString, randomUUID().toString, randomUUID().toString)
    CacheKeyFromArguments.buildCacheKey {
      Caching.memoizeSyncWithProvider(Some(cacheKey.toString()))(ttl) {
        logger.debug(s"AuthUser.updateComputedLocale(sessionId = $sessionId, computedLocale = $computedLocale)")
        getCurrentUser.map(_.userPrimaryKey.value) match {
          case Full(id) =>
            Users.users.vend.getResourceUserByResourceUserId(id).map {
              u =>
                u.LastUsedLocale(computedLocale).save
                logger.debug(s"ResourceUser.LastUsedLocale is saved for the resource user id: $id")
            }.isDefined
          case _ => true// There is no current user
        }
      }
    }
  }
  
  
  /**
    * Find current ResourceUser from the server. 
    * This method has no parameters, it depends on different login types:
    *  AuthUser:  AuthUser.currentUser
    *  OAuthHandshake: OAuthHandshake.getUser
    *  DirectLogin: DirectLogin.getUser
    * to get the current Resourceuser .
    *
    */
  def getCurrentUser: Box[User] = {
    val authorization: Box[String] = S.request.map(_.header("Authorization")).flatten
    val directLogin: Box[String] = S.request.map(_.header("DirectLogin")).flatten
    for {
      resourceUser <- if (AuthUser.currentUser.isDefined){
        //AuthUser.currentUser.get.user.foreign // this will be issue when the resource user is in remote side {
        val user = AuthUser.currentUser.openOrThrowException(ErrorMessages.attemptedToOpenAnEmptyBox)
        // In case that the provider is empty field we default to "local_identity_provider" or "hostname"
        val provider = 
          if(user.provider.get == null || user.provider.get.isEmpty) 
            Constant.localIdentityProvider 
          else 
            user.provider.get
        Users.users.vend.getUserByProviderAndUsername(provider, user.username.get)
      } else if (directLogin.isDefined) // Direct Login
        DirectLogin.getUser
      else if (hasDirectLoginHeader(authorization)) // Direct Login Deprecated
        DirectLogin.getUser
      else if (hasAnOAuthHeader(authorization)) {
        OAuthHandshake.getUser
      } else if (hasGatewayHeader(authorization)){
        GatewayLogin.getUser
      } else {
        logger.debug(ErrorMessages.CurrentUserNotFoundException)
        Failure(ErrorMessages.CurrentUserNotFoundException)
      }
    } yield {
      resourceUser
    }
  }
  /**
   * get current user.
    * Note: 1. it will call getCurrentUser method, 
    *          
   */
  def getCurrentUserUsername: String = {
     getCurrentUser match {
       case Full(user) if user.provider.contains("google")  && !user.emailAddress.isEmpty => user.emailAddress
       case Full(user) if user.provider.contains("yahoo")  && !user.emailAddress.isEmpty => user.emailAddress
       case Full(user) if user.provider.contains("microsoft")  && !user.emailAddress.isEmpty => user.emailAddress
       case Full(user) => user.name
       case _ => "" //TODO need more error handling for different user cases
     }
  }
  
  def getIDTokenOfCurrentUser(): String = {
    if(APIUtil.getPropsAsBoolValue("openid_connect.show_tokens", false)) {
      AuthUser.currentUser match {
        case Full(authUser) =>
          TokensOpenIDConnect.tokens.vend.getOpenIDConnectTokenByAuthUser(authUser.id.get).map(_.idToken).getOrElse("")
        case _ => ""
      }
    } else { 
      "This information is not allowed at this instance."
    }
  }  
  def getAccessTokenOfCurrentUser(): String = {
    if(APIUtil.getPropsAsBoolValue("openid_connect.show_tokens", false)) {
      AuthUser.currentUser match {
        case Full(authUser) =>
          TokensOpenIDConnect.tokens.vend.getOpenIDConnectTokenByAuthUser(authUser.id.get).map(_.accessToken).getOrElse("")
        case _ => ""
      }
    } else { 
      "This information is not allowed at this instance."
    }
  }
  
  /**
    *  get current user.userId
    *  Note: 1.resourceuser has two ids: id(Long) and userid_(String),
    *        
    * @return return userid_(String).
    */
  
  def getCurrentResourceUserUserId: String = {
    getCurrentUser match{
      case Full(user) => user.userId
      case _ => "" //TODO need more error handling for different user cases
    }
  }

  /**
    * The string that's generated when the user name is not found.  By
    * default: S.?("email.address.not.found")
    * The function is overridden in order to prevent leak of information at password reset page if username / email exists or do not exist.
    * I.e. we want to prevent case in which an anonymous user can get information from the message does some username/email exist or no in our system.
    */
  override def userNameNotFoundString: String = "Thank you. If we found a matching user, password reset instructions have been sent."


  /**
   * Overridden to use the hostname set in the props file
   */
  override def sendPasswordReset(name: String) {
    findAuthUserByUsernameLocallyLegacy(name).toList ::: findUsersByEmailLocally(name) map {
      // reason of case parameter name is "u" instead of "user": trait AuthUser have constant mumber name is "user"
      // So if the follow case paramter name is "user" will cause compile warnings
      case u if u.validated_? =>
        u.resetUniqueId().save
        //NOTE: here, if server_mode = portal, so we need modify the resetLink to portal_hostname, then developer can get proper response..
        val resetPasswordLinkProps = Constant.HostName
        val resetPasswordLink = APIUtil.getPropsValue("portal_hostname", resetPasswordLinkProps)+
          passwordResetPath.mkString("/", "/", "/")+urlEncode(u.getUniqueId())
        Mailer.sendMail(From(emailFrom),Subject(passwordResetEmailSubject + " - " + u.username),
          To(u.getEmail) ::
            generateResetEmailBodies(u, resetPasswordLink) :::
            (bccEmail.toList.map(BCC(_))) :_*)
      case u =>
        sendValidationEmail(u)
    }
    // In order to prevent any leakage of information we use the same message for all cases
    S.notice(userNameNotFoundString)
    S.redirectTo(homePage)
  }

  override def lostPasswordXhtml = {
    <div id="recover-password" tabindex="-1">
          <h1>Recover Password</h1>
          <div id="recover-password-explanation">Enter your email address or username and we'll email you a link to reset your password</div>
          <form action={ObpS.uri} method="post">
            <div class="form-group">
              <label>Username or email address</label> <span id="recover-password-email"><input id="email" type="text" /></span>
            </div>
            <div id="recover-password-submit">
              <input type="submit" />
            </div>
          </form>
    </div>
  }

  override def lostPassword = {
    val bind =
          "#email" #> SHtml.text("", sendPasswordReset _) &
          "type=submit" #> lostPasswordSubmitButton(S.?("submit"))

    bind(lostPasswordXhtml)
  }

  //override def def passwordResetMailBody(user: TheUserType, resetLink: String): Elem = { }

  /**
   * Overridden to use the hostname set in the props file
   */
  override def sendValidationEmail(user: TheUserType) {
    val resetLink = Constant.HostName+"/"+validateUserPath.mkString("/")+
      "/"+urlEncode(user.getUniqueId())

    val email: String = user.getEmail

    val msgXml = signupMailBody(user, resetLink)

    Mailer.sendMail(From(emailFrom),Subject(signupMailSubject),
      To(user.getEmail) ::
        generateValidationEmailBodies(user, resetLink) :::
        (bccEmail.toList.map(BCC(_))) :_* )
  }

   def grantDefaultEntitlementsToAuthUser(user: TheUserType) = {
     tryo{getResourceUserByProviderAndUsername(user.getProvider(), user.username.get).head.userId} match {
       case Full(userId)=>APIUtil.grantDefaultEntitlementsToNewUser(userId)
       case _ => logger.error("Can not getResourceUserByUsername here, so it breaks the grantDefaultEntitlementsToNewUser process.")
     }
   }

  override def validateUser(id: String): NodeSeq = findUserByUniqueId(id) match {
    case Full(user) if !user.validated_? =>
      user.setValidated(true).resetUniqueId().save
      grantDefaultEntitlementsToAuthUser(user)
      logUserIn(user, () => {
        S.notice(S.?("account.validated"))
        APIUtil.getPropsValue("user_account_validated_redirect_url") match {
          case Full(redirectUrl) =>
            logger.debug(s"user_account_validated_redirect_url = $redirectUrl")
            S.redirectTo(redirectUrl)
          case _ =>
            logger.debug(s"user_account_validated_redirect_url is NOT defined")
            S.redirectTo(homePage)
        }
      })

    case _ => S.error(S.?("invalid.validation.link")); S.redirectTo(homePage)
  }

  override def actionsAfterSignup(theUser: TheUserType, func: () => Nothing): Nothing = {
    theUser.setValidated(skipEmailValidation).resetUniqueId()
    theUser.save
    val privacyPolicyValue: String = getWebUiPropsValue("webui_privacy_policy", "")
    val termsAndConditionsValue: String = getWebUiPropsValue("webui_terms_and_conditions", "")
    // User Agreement table
    UserAgreementProvider.userAgreementProvider.vend.createOrUpdateUserAgreement(
      theUser.user.foreign.map(_.userId).getOrElse(""), "privacy_conditions", privacyPolicyValue)
    UserAgreementProvider.userAgreementProvider.vend.createOrUpdateUserAgreement(
      theUser.user.foreign.map(_.userId).getOrElse(""), "terms_and_conditions", termsAndConditionsValue)
    if (!skipEmailValidation) {
      sendValidationEmail(theUser)
      S.notice(S.?("sign.up.message"))
      func()
    } else {
      grantDefaultEntitlementsToAuthUser(theUser)
      logUserIn(theUser, () => {
        S.notice(S.?("welcome"))
        func()
      })
    }
  }
  /**
   * Set this to redirect to a certain page after a failed login
   */
  object failedLoginRedirect extends SessionVar[Box[String]](Empty) {
    override lazy val __nameSalt = Helpers.nextFuncName
  }


  def agreeTermsDiv = {
    val webUi = new WebUI
    val webUiPropsValue = getWebUiPropsValue("webui_terms_and_conditions", "")
    val termsAndConditionsCheckboxTitle = Helper.i18n("terms_and_conditions_checkbox_text", Some("I agree to the above Terms and Conditions"))
    val termsAndConditionsCheckboxLabel = Helper.i18n("terms_and_conditions_checkbox_label", Some("Terms and Conditions"))
    val agreeTermsHtml = s"""<hr>
                |                        <div class="form-group" id="terms-and-conditions-div" onclick="enableDisableButton()">
                |                            <details open style="cursor:s-resize;">
                |                                <summary style="display:list-item;"><a class="api_group_name">$termsAndConditionsCheckboxLabel</a></summary>
                |                                <div id="terms-and-conditions-page">${webUi.makeHtml(webUiPropsValue)}</div>
                |                            </details>
                |                            <input type="checkbox" class="form-check-input" id="terms_checkbox" >
                |                            <label id="terms_checkbox_value" class="form-check-label" for="terms_checkbox">$termsAndConditionsCheckboxTitle</label>
                |                        </div>
                |                        """.stripMargin

    scala.xml.Unparsed(agreeTermsHtml)
  }

  def legalNoticeDiv = {
    val agreeTermsHtml = getWebUiPropsValue("webui_legal_notice_html_text", "")
    if(agreeTermsHtml.isEmpty){
      s""
    } else{
      scala.xml.Unparsed(s"""$agreeTermsHtml""")
    }
  }

  def agreePrivacyPolicy = {
    val webUi = new WebUI
    val privacyPolicyCheckboxText = Helper.i18n("privacy_policy_checkbox_text", Some("I agree to the above Privacy Policy"))
    val privacyPolicyCheckboxLabel = Helper.i18n("privacy_policy_checkbox_label", Some("Privacy Policy"))
    val webUiPropsValue = getWebUiPropsValue("webui_privacy_policy", "")
    val agreePrivacyPolicy = s"""<hr>
                           |                        <div class="form-group" id="privacy-conditions-div" onclick="enableDisableButton()">
                           |                            <details open style="cursor:s-resize;">
                           |                                <summary style="display:list-item;"><a class="api_group_name">$privacyPolicyCheckboxLabel</a></summary>
                           |                                <div id="privacy-policy-page">${webUi.makeHtml(webUiPropsValue)}</div>
                           |                            </details>
                           |                            <input id="privacy_checkbox" type="checkbox" class="form-check-input">
                           |                            <label class="form-check-label" for="privacy_checkbox">$privacyPolicyCheckboxText</label>
                           |                        </div>
                           |                        <hr>""".stripMargin

    scala.xml.Unparsed(agreePrivacyPolicy)
  }
  def enableDisableSignUpButton = {
    val javaScriptCode = """<script>
                               |                function enableDisableButton() {
                               |                  var checkBox = document.getElementById("terms-and-conditions-div").querySelector("input[type=checkbox]");
                               |                  var checkBox2 = document.getElementById("privacy-conditions-div").querySelector("input[type=checkbox]");
                               |                  var button = document.getElementById("submit-button");
                               |                  if (checkBox.checked == true && checkBox2.checked == true){
                               |                    button.disabled = false;
                               |                  } else {
                               |                     button.disabled = true;
                               |                  }
                               |                }
                               |                </script>""".stripMargin

    scala.xml.Unparsed(javaScriptCode)
  }

  def signupFormTitle = getWebUiPropsValue("webui_signup_form_title_text", S.?("sign.up"))

  override def signupXhtml (user:AuthUser) =  {
    <div id="signup" tabindex="-1">
      <form method="post" action={ObpS.uriAndQueryString.getOrElse(ObpS.uri)}>
          <h1>{signupFormTitle}</h1>
          {legalNoticeDiv}
          <div id="signup-general-error" class="alert alert-danger hide"><span data-lift="Msg?id=error"/></div>
          {localForm(user, false, signupFields)}
          {agreeTermsDiv}
          {agreePrivacyPolicy}
          <div id="signup-submit">
            <input onmouseover="enableDisableButton()" onfocus="enableDisableButton()" disabled="true" id="submit-button" type="submit" class="btn btn-danger"/>
          </div>
          {enableDisableSignUpButton}
      </form>
    </div>
  }


  override def localForm(user: TheUserType, ignorePassword: Boolean, fields: List[FieldPointerType]): NodeSeq = {
    for {
      pointer <- fields
      field <- computeFieldFromPointer(user, pointer).toList
      if field.show_? && (!ignorePassword || !pointer.isPasswordField_?)
      form <- field.toForm.toList
    } yield {
      if(field.uniqueFieldId.getOrElse("") == "authuser_password") {
        <div class="form-group">
          <label>{field.displayName}</label>
          {form}
        </div>
      } else {
        <div class="form-group">
          <label>{field.displayName}</label>
          {form}
          <div id="signup-error" class="alert alert-danger hide"><span data-lift={s"Msg?id=${field.uniqueFieldId.getOrElse("")}&errorClass=error"}/></div>
        </div>
      }
    }

  }

  def userLoginFailed = {
    logger.info("failed: " + failedLoginRedirect.get)
    // variable redir is from failedLoginRedirect, it is set-up in OAuthAuthorisation.scala as following code:
    // val currentUrl = ObpS.uriAndQueryString.getOrElse("/")
    // AuthUser.failedLoginRedirect.set(Full(Helpers.appendParams(currentUrl, List((FailedLoginParam, "true")))))
    val redir = failedLoginRedirect.get

    //Check the internal redirect, in case for open redirect issue.
    // variable redir is from loginRedirect, it is set-up in OAuthAuthorisation.scala as following code:
    // val currentUrl = ObpS.uriAndQueryString.getOrElse("/")
    // AuthUser.loginRedirect.set(Full(Helpers.appendParams(currentUrl, List((LogUserOutParam, "false")))))
    if (Helper.isValidInternalRedirectUrl(redir.toString)) {
        S.redirectTo(redir.toString)
    } else {
      S.error(S.?(ErrorMessages.InvalidInternalRedirectUrl))
      logger.info(ErrorMessages.InvalidInternalRedirectUrl + loginRedirect.get)
    }
    S.error("login", S.?("Invalid Username or Password"))
  }




  def getResourceUserId(username: String, password: String): Box[Long] = {
    findAuthUserByUsernameLocallyLegacy(username) match {
      // We have a user from the local provider.
      case Full(user) if (user.getProvider() == Constant.localIdentityProvider) =>
        if (
          user.validated_? &&
          // User is NOT locked AND the password is good
          ! LoginAttempt.userIsLocked(user.getProvider(), username) &&
          user.testPassword(Full(password)))
            {
              // We logged in correctly, so reset badLoginAttempts counter (if it exists)
              LoginAttempt.resetBadLoginAttempts(user.getProvider(), username)
              Full(user.user.get) // Return the user.
            }
        // User is unlocked AND password is bad
        else if (
          user.validated_? &&
          ! LoginAttempt.userIsLocked(user.getProvider(), username) &&
          ! user.testPassword(Full(password))
        ) {
          LoginAttempt.incrementBadLoginAttempts(user.getProvider(), username)
          Empty
        }
        // User is locked
        else if (LoginAttempt.userIsLocked(user.getProvider(), username))
        {
          LoginAttempt.incrementBadLoginAttempts(user.getProvider(), username)
          logger.info(ErrorMessages.UsernameHasBeenLocked)
          //TODO need to fix, use Failure instead, it is used to show the error message to the GUI
          Full(usernameLockedStateCode)
        }
        else {
          // Nothing worked, so just increment bad login attempts
          LoginAttempt.incrementBadLoginAttempts(user.getProvider(), username)
          Empty
        }
      // We have a user from an external provider.
      case Full(user) if (user.getProvider() != Constant.localIdentityProvider) =>
        APIUtil.getPropsAsBoolValue("connector.user.authentication", false) match {
            case true if !LoginAttempt.userIsLocked(user.getProvider(), username) =>
              val userId =
                for {
                  authUser <- checkExternalUserViaConnector(username, password)
                  resourceUser <- tryo {
                    authUser.user
                  }
                } yield {
                  LoginAttempt.resetBadLoginAttempts(user.getProvider(), username)
                  resourceUser.get
                }
              userId match {
                case Full(l: Long) => Full(l)
                case _ =>
                  LoginAttempt.incrementBadLoginAttempts(user.getProvider(), username)
                  Empty
              }
            case false =>
              LoginAttempt.incrementBadLoginAttempts(user.getProvider(), username)
              Empty
          }
      // Everything else.
      case _ =>
        LoginAttempt.incrementBadLoginAttempts(user.foreign.map(_.provider).getOrElse(Constant.HostName), username)
        Empty
    }
  }

  /**
    * This method is belong to AuthUser, it is used for authentication(Login stuff)
    * 1 get the user over connector.
    * 2 check whether it is existing in AuthUser table in obp side.
    * 3 if not existing, will create new AuthUser.
    * @return Return the authUser
    */
  @deprecated("we have @checkExternalUserViaConnector method ","01-07-2020")
  def getUserFromConnector(name: String, password: String):Box[AuthUser] = {
    Connector.connector.vend.getUser(name, password) match {
      case Full(InboundUser(extEmail, extPassword, extUsername)) => {
        val extProvider = connector
        val user = findAuthUserByUsernameLocallyLegacy(name) match {
          // Check if the external user is already created locally
          case Full(user) if user.validated_?
            // && user.provider == extProvider
            => {
            // Return existing user if found
            logger.info("external user already exists locally, using that one")
            user
          }

          // If not found, create a new user
          case _ =>
            // Create AuthUser using fetched data from connector
            // assuming that user's email is always validated
            logger.info("external user "+ extEmail +" does not exist locally, creating one")
            val newUser = AuthUser.create
              .firstName(extUsername)
              .email(extEmail)
              .username(extUsername)
              // No need to store password, so store dummy string instead
              .password(generateUUID())
              .provider(extProvider)
              .validated(true)
            // Return created user
            newUser.saveMe()
        }
        Full(user)
      }
      case _ => {
        Empty
      }
    }
  }
  /**
    * This method is belong to AuthUser, it is used for authentication(Login stuff)
    * 1 get the user over connector.
    * 2 check whether it is existing in AuthUser table in obp side.
    * 3 if not existing, will create new AuthUser.
    * @return Return the authUser
    */
  def checkExternalUserViaConnector(username: String, password: String):Box[AuthUser] = {
    Connector.connector.vend.checkExternalUserCredentials(username, password, None) match {
      case Full(InboundExternalUser(aud, exp, iat, iss, sub, azp, email, emailVerified, name, userAuthContexts)) =>
        val user = findAuthUserByUsernameAndProvider(sub, iss) match { // Check if the external user is already created locally
          case Full(user) if user.validated_? => // Return existing user if found
            logger.debug("external user already exists locally, using that one")
            userAuthContexts match {
              case Some(authContexts) => // Write user auth context to the database
                UserAuthContextProvider.userAuthContextProvider.vend.createOrUpdateUserAuthContexts(user.userIdAsString, authContexts)
              case None => // Do nothing
            }
            user
          case _ => // If not found, create a new user
            // Create AuthUser using fetched data from connector
            // assuming that user's email is always validated
            logger.debug("external user "+ sub + " does not exist locally, creating one")
            AuthUser.create
              .firstName(name.getOrElse(sub))
              .email(email.getOrElse(""))
              .username(sub)
              // No need to store password, so store dummy string instead
              .password(generateUUID())
              // TODO add field stating external password check only.
              .provider(iss)
              .validated(emailVerified.exists(_.equalsIgnoreCase("true")))
              .saveMe() //NOTE, we will create the resourceUser in the `saveMe()` method.
        }
        userAuthContexts match {
          case Some(authContexts) => { // Write user auth context to the database
              // get resourceUserId from AuthUser.
              val resourceUserId = user.user.foreign.map(_.userId).getOrElse("")
              // we try to catch this exception, the createOrUpdateUserAuthContexts can not break the login process.
              tryo {UserAuthContextProvider.userAuthContextProvider.vend.createOrUpdateUserAuthContexts(resourceUserId, authContexts)}
                .openOr(logger.error(s"${resourceUserId} checkExternalUserViaConnector.createOrUpdateUserAuthContexts throw exception! "))
          }
          case None => // Do nothing
        }
        Full(user)
      case _ =>
        Empty
    }
  }



def restoreSomeSessions(): Unit = {
  activeBrand()
}

  override protected def capturePreLoginState(): () => Unit = () => {restoreSomeSessions}


  /**
    * The LocParams for the menu item for login.
    * Overridden in order to add custom error message. Attention: Not calling super will change the default behavior!
    */
  override protected def loginMenuLocParams: List[LocParam[Unit]] = {
    If(notLoggedIn_? _, () => RedirectResponse("/already-logged-in")) ::
      Template(() => wrapIt(login)) ::
      Nil
  }


  //overridden to allow a redirection if login fails
  /**
    * Success cases:
    *  case1: user validated && user not locked && user.provider from localhost && password correct --> Login in
    *  case2: user validated && user not locked && user.provider not localhost  && password correct --> Login in
    *  case3: user from remote && checked over connector --> Login in
    *
    * Error cases:
    *  case1: user is locked --> UsernameHasBeenLocked
    *  case2: user.validated_? --> account.validation.error
    *  case3: right username but wrong password --> Invalid Login Credentials
    *  case4: wrong username   --> Invalid Login Credentials
    *  case5: UnKnow error     --> UnexpectedErrorDuringLogin
    */
  override def login: NodeSeq = {
    // This query parameter is specific to ORY Hydra login request
    val loginChallenge: Box[String] = ObpS.param("login_challenge").or(S.getSessionAttribute("login_challenge"))
    def redirectUri(user: Box[ResourceUser]): String = {
      val userId = user.map(_.userId).getOrElse("")
      val hashedAgreementTextOfUser =
        UserAgreementProvider.userAgreementProvider.vend.getUserAgreement(userId, "terms_and_conditions")
          .map(_.agreementHash).getOrElse(HashUtil.Sha256Hash("not set"))
      val agreementText = getWebUiPropsValue("webui_terms_and_conditions", "not set")
      val hashedAgreementText = HashUtil.Sha256Hash(agreementText)
      if(hashedAgreementTextOfUser == hashedAgreementText) { // Chech terms and conditions
        val hashedAgreementTextOfUser =
          UserAgreementProvider.userAgreementProvider.vend.getUserAgreement(userId, "privacy_conditions")
            .map(_.agreementHash).getOrElse(HashUtil.Sha256Hash("not set"))
        val agreementText = getWebUiPropsValue("webui_privacy_policy", "not set")
        val hashedAgreementText = HashUtil.Sha256Hash(agreementText)
        if(hashedAgreementTextOfUser == hashedAgreementText) { // Check privacy policy
          loginRedirect.get match {
            case Full(url) =>
              loginRedirect(Empty)
              url
            case _ =>
              homePage
          }
        } else {
          "/privacy-policy"
        }
      } else {
        "/terms-and-conditions"
      }

    }
    //Check the internal redirect, in case for open redirect issue.
    // variable redirect is from loginRedirect, it is set-up in OAuthAuthorisation.scala as following code:
    // val currentUrl = ObpS.uriAndQueryString.getOrElse("/")
    // AuthUser.loginRedirect.set(Full(Helpers.appendParams(currentUrl, List((LogUserOutParam, "false")))))
    def checkInternalRedirectAndLogUserIn(preLoginState: () => Unit, redirect: String, user: AuthUser) = {
      if (Helper.isValidInternalRedirectUrl(redirect)) {
        logUserIn(user, () => {
          S.notice(S.?("logged.in"))
          preLoginState()
          if(emailDomainToSpaceMappings.nonEmpty){
            Future{
              tryo{AuthUser.grantEntitlementsToUseDynamicEndpointsInSpaces(user)}
                .openOr(logger.error(s"${user} checkInternalRedirectAndLogUserIn.grantEntitlementsToUseDynamicEndpointsInSpaces throw exception! "))
            }}
          if(emailDomainToEntitlementMappings.nonEmpty){
            Future{
                tryo{AuthUser.grantEmailDomainEntitlementsToUser(user)}
                  .openOr(logger.error(s"${user} checkInternalRedirectAndLogUserIn.grantEmailDomainEntitlementsToUser throw exception! "))
            }}
          // We use Hydra as an Headless Identity Provider which implies OBP-API must provide User Management.
          // If there is the query parameter login_challenge in a url we know it is tha Hydra request
          // TODO Write standalone application for Login and Consent Request of Hydra as Identity Provider
          integrateWithHydra match {
            case true =>
              if (loginChallenge.isEmpty == false) {
                val acceptLoginRequest = new AcceptLoginRequest
                val adminApi: AdminApi = new AdminApi
                acceptLoginRequest.setSubject(user.username.get)
                val result = adminApi.acceptLoginRequest(loginChallenge.getOrElse(""), acceptLoginRequest)
                S.redirectTo(result.getRedirectTo)
              } else {
                S.redirectTo(redirect)
              }
            case false =>
              S.redirectTo(redirect)
          }
        })
      } else {
        S.error(S.?(ErrorMessages.InvalidInternalRedirectUrl))
        logger.info(ErrorMessages.InvalidInternalRedirectUrl + loginRedirect.get)
      }
    }

    def isObpProvider(user: AuthUser) = {
      user.getProvider() == Constant.localIdentityProvider
    }

    def obpUserIsValidatedAndNotLocked(usernameFromGui: String, user: AuthUser) = {
      user.validated_? && !LoginAttempt.userIsLocked(user.getProvider(), usernameFromGui) &&
        isObpProvider(user)
    }

    def externalUserIsValidatedAndNotLocked(usernameFromGui: String, user: AuthUser) = {
      user.validated_? && !LoginAttempt.userIsLocked(user.getProvider(), usernameFromGui) &&
        !isObpProvider(user)
    }

    def loginAction = {
      if (S.post_?) {
        val usernameFromGui = ObpS.param("username").getOrElse("")
        val passwordFromGui = ObpS.param("password").getOrElse("")
        val usernameEmptyField = ObpS.param("username").map(_.isEmpty()).getOrElse(true)
        val passwordEmptyField = ObpS.param("password").map(_.isEmpty()).getOrElse(true)
        val emptyField = usernameEmptyField || passwordEmptyField
        emptyField match {
          case true =>
            if(usernameEmptyField)
              S.error("login-form-username-error", Helper.i18n("please.enter.your.username"))
            if(passwordEmptyField)
              S.error("login-form-password-error", Helper.i18n("please.enter.your.password"))
          case false =>
            findAuthUserByUsernameLocallyLegacy(usernameFromGui) match {
              case Full(user) if !user.validated_? =>
                S.error(S.?("account.validation.error"))

              // Check if user comes from localhost and
              case Full(user) if obpUserIsValidatedAndNotLocked(usernameFromGui, user) =>
                if(user.testPassword(Full(passwordFromGui))) { // if User is NOT locked and password is good
                  // Reset any bad attempt
                  LoginAttempt.resetBadLoginAttempts(user.getProvider(), usernameFromGui)
                  val preLoginState = capturePreLoginState()
                  // User init actions
                  AfterApiAuth.innerLoginUserInitAction(Full(user))
                  logger.info("login redirect: " + loginRedirect.get)
                  val redirect = redirectUri(user.user.foreign)
                  checkInternalRedirectAndLogUserIn(preLoginState, redirect, user)
                } else { // If user is NOT locked AND password is wrong => increment bad login attempt counter.
                  LoginAttempt.incrementBadLoginAttempts(user.getProvider(),usernameFromGui)
                  S.error(Helper.i18n("invalid.login.credentials"))
                }

              // If user is locked, send the error to GUI
              case Full(user) if LoginAttempt.userIsLocked(user.getProvider(), usernameFromGui) =>
                LoginAttempt.incrementBadLoginAttempts(user.getProvider(),usernameFromGui)
                S.error(S.?(ErrorMessages.UsernameHasBeenLocked))
                loginRedirect(ObpS.param("Referer").or(S.param("Referer")))

              // Check if user came from kafka/obpjvm/stored_procedure and
              // if User is NOT locked. Then check username and password
              // from connector in case they changed on the south-side
              case Full(user) if externalUserIsValidatedAndNotLocked(usernameFromGui, user) && testExternalPassword(usernameFromGui, passwordFromGui) =>
                  // Reset any bad attempts
                  LoginAttempt.resetBadLoginAttempts(user.getProvider(), usernameFromGui)
                  val preLoginState = capturePreLoginState()
                  logger.info("login redirect: " + loginRedirect.get)
                  val redirect = redirectUri(user.user.foreign)
                  //This method is used for connector = kafka* || obpjvm*
                  //It will update the views and createAccountHolder ....
                  registeredUserHelper(user.getProvider(),user.username.get)
                  // User init actions
                  AfterApiAuth.innerLoginUserInitAction(Full(user))
                  checkInternalRedirectAndLogUserIn(preLoginState, redirect, user)


              // Error case:
              // the username exist but provider cannot be matched
              // It can happen via next scenario:
              //   - sign up user at some obp-api cluster
              //   - change a url of the cluster
              //   - try to log on user at the cluster
              case Full(user) if !isObpProvider(user) =>
                S.error(S.?(s"${ErrorMessages.InvalidProviderUrl} Actual: ${Constant.localIdentityProvider}, Expected: ${user.provider}"))


              // If user cannot be found locally, try to authenticate user via connector
              case Empty if (APIUtil.getPropsAsBoolValue("connector.user.authentication", false) ||
                APIUtil.getPropsAsBoolValue("kafka.user.authentication", false) ) =>

                val preLoginState = capturePreLoginState()
                logger.info("login redirect: " + loginRedirect.get)
                val redirect = redirectUri(user.foreign)
                externalUserHelper(usernameFromGui, passwordFromGui) match {
                    case Full(user: AuthUser) =>
                      LoginAttempt.resetBadLoginAttempts(user.getProvider(), usernameFromGui)
                      // User init actions
                      AfterApiAuth.innerLoginUserInitAction(Full(user))
                      checkInternalRedirectAndLogUserIn(preLoginState, redirect, user)
                    case _ =>
                      LoginAttempt.incrementBadLoginAttempts(user.foreign.map(_.provider).getOrElse(Constant.HostName), username.get)
                      Empty
                      S.error(Helper.i18n("invalid.login.credentials"))
                }

              //If there is NO the username, throw the error message.
              case Empty =>
                S.error(Helper.i18n("invalid.login.credentials"))
              case unhandledCase =>
                logger.error("------------------------------------------------------")
                logger.error(s"username from GUI: $usernameFromGui")
                logger.error("An unexpected login error occurred:")
                logger.error(unhandledCase)
                logger.error("------------------------------------------------------")
                LoginAttempt.incrementBadLoginAttempts(user.foreign.map(_.provider).getOrElse(Constant.HostName), usernameFromGui)
                S.error(S.?(ErrorMessages.UnexpectedErrorDuringLogin)) // Note we hit this if user has not clicked email validation link
            }
        }
      }
    }

    // In this function we bind submit button to loginAction function.
    // In case that unique token of submit button cannot be paired submit action will be omitted.
    // Implemented in order to prevent a CSRF attack
    def insertSubmitButton = {
      scala.xml.XML.loadString(loginSubmitButton(loginButtonText, loginAction _).toString().replace("type=\"submit\"","class=\"submit\" type=\"submit\""))
    }

    val bind =
          "submit" #> insertSubmitButton
   bind(loginXhtml)
  }

  override def logout = {
    logoutCurrentUser
    S.request match {
      case Full(a) =>  a.param("redirect") match {
        case Full(customRedirect) => S.redirectTo(customRedirect)
        case _ => S.redirectTo(homePage)
      }
      case _ => S.redirectTo(homePage)
    }
  }


  /**
    * The user authentications is not exciting in obp side, it need get the user via connector
    */
 def testExternalPassword(usernameFromGui: String, passwordFromGui: String): Boolean = {
   // TODO Remove kafka and obpjvm special cases
   if (connector.startsWith("kafka")) {
     getUserFromConnector(usernameFromGui, passwordFromGui) match {
       case Full(user:AuthUser) => true
       case _ => false
     }
   } else {
     checkExternalUserViaConnector(usernameFromGui, passwordFromGui) match {
       case Full(user:AuthUser) => true
       case _ => false
     }
   }
  }

  /**
    * This method will update the views and createAccountHolder ....
    */
  def externalUserHelper(name: String, password: String): Box[AuthUser] = {
    // TODO Remove kafka and obpjvm special cases
    if (connector.startsWith("kafka") ) {
      for {
       user <- getUserFromConnector(name, password)
       u <- Users.users.vend.getUserByProviderAndUsername(user.getProvider(), name)
      } yield {
        user
      }
    } else {
      for {
        user <- checkExternalUserViaConnector(name, password)
        u <- Users.users.vend.getUserByProviderAndUsername(user.getProvider(), name)
      } yield {
        user
      }
    }
  }


  /**
    * This method will update the views and createAccountHolder ....
    */
  def registeredUserHelper(provider: String,  username: String) = {
    if (connector.startsWith("kafka")) {
      for {
       u <- Users.users.vend.getUserByProviderAndUsername(provider, username)
      } yield {
        refreshUserLegacy(u, None)
      }
    }
  }

  /**
   * A Space is an alias for the OBP Bank. Each Bank / Space can contain many Dynamic Endpoints. If a User belongs to a Space,
   * the User can use those endpoints but not modify them. If a User creates a Bank (aka Space) the user can create
   * and modify Dynamic Endpoints and other objects in that Bank / Space.
   *
   * @return
   */
  def mySpaces(user: AuthUser): List[BankId] = {
    //1st: first check the user is validated
    if (user.validated_?) {
      //userEmail = robert.uk.29@example.com
      // 2st get the email domain - `example.com`
      val emailDomain = StringUtils.substringAfterLast(user.email.get, "@")

      //3 return the bankIds
      emailDomainToSpaceMappings.collectFirst {
        case EmailDomainToSpaceMapping(`emailDomain`, ids) => ids.map(BankId(_));
      } getOrElse Nil

    } else {
      Nil
    }
  }

  def grantEntitlementsToUseDynamicEndpointsInSpaces(user: AuthUser) = {
    if(emailDomainToSpaceMappings.nonEmpty) {
      val createdByProcess = "grantEntitlementsToUseDynamicEndpointsInSpaces"
      val userId = user.user.obj.map(_.userId).getOrElse("")

      // user's already auto granted entitlements.
      val entitlementsGrantedByThisProcess = Entitlement.entitlement.vend.getEntitlementsByUserId(userId)
        .map(_.filter(role => role.createdByProcess == createdByProcess))
        .getOrElse(Nil)

      def alreadyHasEntitlement(role:ApiRole, bankId: String): Boolean =
        entitlementsGrantedByThisProcess.exists(entitlement => entitlement.roleName == role.toString() && entitlement.bankId == bankId)

      //call mySpaces --> get BankIds --> listOfRolesToUseAllDynamicEndpointsAOneBank (at each bank)--> Grant roles (for each role)
      val allCurrentDynamicRoleToBankIdPairs: List[(ApiRole, String)] = for {
        BankId(bankId) <- mySpaces(user: AuthUser)
        role <- DynamicEndpointHelper.listOfRolesToUseAllDynamicEndpointsAOneBank(Some(bankId))
      } yield {
        if (!alreadyHasEntitlement(role, bankId)) {
          Entitlement.entitlement.vend.addEntitlement(bankId, userId, role.toString, createdByProcess)
        }

        role -> bankId
      }

      // if user's auto granted entitlement invalid, delete it.
      // invalid happens when some dynamic endpoints are removed, so the entitlements linked to the deleted dynamic endpoints are invalid.
      for {
        grantedEntitlement <- entitlementsGrantedByThisProcess
        grantedEntitlementRoleName = grantedEntitlement.roleName
        grantedEntitlementBankId = grantedEntitlement.bankId
      } {
        val isInValidEntitlement = !allCurrentDynamicRoleToBankIdPairs.exists { roleToBankIdPair =>
          val(role, roleBankId) = roleToBankIdPair
          role.toString() == grantedEntitlementRoleName && roleBankId == grantedEntitlementBankId
        }

        if(isInValidEntitlement) {
          Entitlement.entitlement.vend.deleteEntitlement(Full(grantedEntitlement))
        }
      }
    }
  }

  def grantEmailDomainEntitlementsToUser(user: AuthUser) = {
    if(emailDomainToEntitlementMappings.nonEmpty){
      val createdByProcess = "grantEmailDomainEntitlementsToUser"
      val userId = user.user.obj.map(_.userId).getOrElse("")

      // user's already auto granted entitlements.
      val entitlementsGrantedByThisProcess = Entitlement.entitlement.vend.getEntitlementsByUserId(userId)
        .map(_.filter(role => role.createdByProcess == createdByProcess))
        .getOrElse(Nil)

      def alreadyHasEntitlement(bankId: String, roleName:String): Boolean =
        entitlementsGrantedByThisProcess.exists(entitlement => entitlement.roleName == roleName && entitlement.bankId == bankId)

      val allEntitlementsFromCurrentProps: List[(String, String)] = for{
        emailDomainToEntitlementMapping <- emailDomainToEntitlementMappings
        domain = emailDomainToEntitlementMapping.domain
        entitlement <- emailDomainToEntitlementMapping.entitlements if StringUtils.substringAfterLast(user.email.get, "@") == domain
        roleName = entitlement.role_name
        roleBankId = entitlement.bank_id
      } yield {
        if (!alreadyHasEntitlement(roleBankId, roleName)) {
          Entitlement.entitlement.vend.addEntitlement(roleBankId, userId, roleName, createdByProcess)
        }
        roleName -> roleBankId
      }

      // if user's auto granted entitlement invalid, delete it.
      // invalid happens when some dynamic endpoints are removed, so the entitlements linked to the deleted dynamic endpoints are invalid.
      for {
        grantedEntitlement <- entitlementsGrantedByThisProcess
        grantedEntitlementRoleName = grantedEntitlement.roleName
        grantedEntitlementBankId = grantedEntitlement.bankId
      } {
        val isInValidEntitlement = !allEntitlementsFromCurrentProps.exists { roleNameToBankIdPair =>
          val(roleName, roleBankId) = roleNameToBankIdPair
          roleName == grantedEntitlementRoleName && roleBankId == grantedEntitlementBankId
        }

        if(isInValidEntitlement) {
          Entitlement.entitlement.vend.deleteEntitlement(Full(grantedEntitlement))
        }
      }
    }
  }

  /**
   * This method is used for onboarding bank customer to OBP.
   *  1st: we will get all the accountsHeld from CBS side.
   *  2rd: we will create the account Holder, view and account accesses.
   */
  def refreshUser(user: User, callContext: Option[CallContext]) = {
    for{
      (accountsHeld, _) <- Connector.connector.vend.getBankAccountsForUser(user.provider, user.name,callContext) map {
        connectorEmptyResponse(_, callContext)
      }
      _ = logger.debug(s"--> for user($user): AuthUser.refreshUser.accountsHeld : ${accountsHeld}")

      success = refreshViewsAccountAccessAndHolders(user, accountsHeld, callContext)

    }yield {
      success
    }
  }

  @deprecated("This return Box, not a future, try to use @refreshUser instead. ","08-09-2023")
  def refreshUserLegacy(user: User, callContext: Option[CallContext]) = {
    for{
      (accountsHeld, _) <- Connector.connector.vend.getBankAccountsForUserLegacy(user.provider, user.name, callContext)

      _ = logger.debug(s"--> for user($user): AuthUser.refreshUserLegacy.accountsHeld : ${accountsHeld}")

      success = refreshViewsAccountAccessAndHolders(user, accountsHeld, callContext)

    }yield {
      success
    }
  }

  /**
    * This is a helper method
    * create/update/delete the views, accountAccess, accountHolders for OBP get accounts from CBS side.
    * This method can only be used by the original user(account holder).
   *  InboundAccount return many fields, but in this method, we only need bankId, accountId and viewId so far.
    */
    def refreshViewsAccountAccessAndHolders(user: User, accountsHeld: List[InboundAccount], callContext: Option[CallContext])  = {
      if(user.isOriginalUser){
        //first, we compare the accounts in obp  and the accounts in cbs,
        val (_, privateAccountAccess) = Views.views.vend.privateViewsUserCanAccess(user)
        val obpAccountAccessBankAccountIds = privateAccountAccess.map(accountAccess =>BankIdAccountId(BankId(accountAccess.bank_id.get), AccountId(accountAccess.account_id.get))).toSet

        // This will return all account held for the user, no mater what the source is.
        val userOwnBankAccountIds = AccountHolders.accountHolders.vend.getAccountsHeldByUser(user)

        //The accounts from AccountAccess may contains other users' account info, so here we filter the accounts By account holder, only show the user's own accounts
        val obpBankAccountIds = obpAccountAccessBankAccountIds.filter(bankAccountId => userOwnBankAccountIds.contains(bankAccountId)).toSet

        //The accounts from AccountAccess may contains other users' account info, so here we filter the accounts By account holder, only show the user's own accounts
        val cbsBankAccountIds = accountsHeld.map(account =>BankIdAccountId(BankId(account.bankId),AccountId(account.accountId))).toSet

        //cbs removed this accounts, but OBP still contains the data for them, so we need to clean data in OBP side.
        val cbsRemovedBankAccountIds = obpBankAccountIds diff cbsBankAccountIds

        //cbs has new accounts which are not in obp yet, we need to create new data for these accounts.
        val csbNewBankAccountIds = cbsBankAccountIds diff obpBankAccountIds

        logger.debug("refreshViewsAccountAccessAndHolders.cbsRemovedBankAccountIds-------"+cbsRemovedBankAccountIds)
        logger.debug("refreshViewsAccountAccessAndHolders.csbNewBankAccountIds-------" + csbNewBankAccountIds)
        //1rd remove the deprecated accounts
        //TODO. need to double check if we need to clean accountidmapping table, account meta data (MappedTag) ....
        for{
          cbsRemovedBankAccountId <- cbsRemovedBankAccountIds
          _ = logger.debug("refreshViewsAccountAccessAndHolders.cbsRemovedBankAccountIds.cbsRemovedBankAccountId: start-------" + cbsRemovedBankAccountId)
          bankId = cbsRemovedBankAccountId.bankId
          accountId = cbsRemovedBankAccountId.accountId
          _ = Views.views.vend.revokeAccountAccessByUser(bankId, accountId, user, callContext)
          _ = AccountHolders.accountHolders.vend.deleteAccountHolder(user,cbsRemovedBankAccountId)
          cbsAccount = accountsHeld.find(cbsAccount =>cbsAccount.bankId == bankId.value && cbsAccount.accountId == accountId.value)
          viewId <- cbsAccount.map(_.viewsToGenerate).getOrElse(List.empty[String])
          _=UserRefreshes.UserRefreshes.vend.createOrUpdateRefreshUser(user.userId)
          success <- Views.views.vend.removeCustomView(ViewId(viewId), cbsRemovedBankAccountId)
          _ = logger.debug("refreshViewsAccountAccessAndHolders.cbsRemovedBankAccountIds.cbsRemovedBankAccountId: finish-------" + cbsRemovedBankAccountId)
        } yield {
          success
        }

        //2st: create views/accountAccess/accountHolders for the new coming accounts
        for {
          newBankAccountId <- csbNewBankAccountIds
          _ = logger.debug("refreshViewsAccountAccessAndHolders.csbNewBankAccountId.newBankAccountId: start-------" + newBankAccountId)
          _ = AccountHolders.accountHolders.vend.getOrCreateAccountHolder(user,newBankAccountId,Some("UserAuthContext"))
          bankId = newBankAccountId.bankId
          accountId = newBankAccountId.accountId
          newBankAccount = accountsHeld.find(cbsAccount =>cbsAccount.bankId == bankId.value && cbsAccount.accountId == accountId.value)
          viewId <- newBankAccount.map(_.viewsToGenerate).getOrElse(List.empty[String])
          view <- Views.views.vend.getOrCreateSystemViewFromCbs(viewId)//TODO, only support system views so far, may add custom views later.
          _=UserRefreshes.UserRefreshes.vend.createOrUpdateRefreshUser(user.userId)
          view <- if (view.isSystem) //if the view is a system view, we will call `grantAccessToSystemView`
              Views.views.vend.grantAccessToSystemView(bankId, accountId, view, user)
            else //otherwise, we will call `grantAccessToCustomView`
              Views.views.vend.grantAccessToCustomView(view.uid, user)
          _ = logger.debug("refreshViewsAccountAccessAndHolders.csbNewBankAccountId.newBankAccountId: finish-------" + newBankAccountId)
        } yield {
          view
        }

        //3rd: if the ids are not change, but views are changed, we still need compare the view for each account:
        if(cbsRemovedBankAccountIds.equals(csbNewBankAccountIds)) {
          for {
            bankAccountId <- obpBankAccountIds
            // we can not get the views from the `viewDefinition` table, because we can not delete system views at all. we need to read the view from accountAccess table.
            //obpViewsForAccount = MapperViews.availableViewsForAccount(bankAccountId).map(_.viewId.value)
            obpViewsForAccount = Views.views.vend.privateViewsUserCanAccessForAccount(user, bankAccountId).map(_.viewId.value)
            _ = logger.debug("refreshViewsAccountAccessAndHolders.obpViewsForAccount-------" + obpViewsForAccount)

            cbsViewsForAccount = accountsHeld.find(account => account.bankId.equals(bankAccountId.bankId.value) && account.accountId.equals(bankAccountId.accountId.value)).map(_.viewsToGenerate).getOrElse(Nil)
            _ = logger.debug("refreshViewsAccountAccessAndHolders.cbsViewsForAccount-------" + cbsViewsForAccount)
            //cbs removed these views, but OBP still contains the data for them, so we need to clean data in OBP side.
            cbsRemovedViewsForAccount = obpViewsForAccount diff cbsViewsForAccount
            _ = logger.debug("refreshViewsAccountAccessAndHolders.cbsRemovedViewsForAccount-------" + cbsRemovedViewsForAccount)
            _ = if(cbsRemovedViewsForAccount.nonEmpty){
              val cbsRemovedBankIdAccountIdViewIds = cbsRemovedViewsForAccount.map(view => BankIdAccountIdViewId(bankAccountId.bankId, bankAccountId.accountId, ViewId(view)))
              Views.views.vend.revokeAccessToMultipleViews(cbsRemovedBankIdAccountIdViewIds, user)
              cbsRemovedViewsForAccount.map(view =>Views.views.vend.removeCustomView(ViewId(view), bankAccountId))
              UserRefreshes.UserRefreshes.vend.createOrUpdateRefreshUser(user.userId)
            }
            //cbs has new views which are not in obp yet, we need to create new data for these accounts.
            csbNewViewsForAccount = cbsViewsForAccount diff obpViewsForAccount
            _ = logger.debug("refreshViewsAccountAccessAndHolders.csbNewViewsForAccount-------" + csbNewViewsForAccount)
            success = if(csbNewViewsForAccount.nonEmpty){
              for{
                newViewForAccount <- csbNewViewsForAccount
                _ = logger.debug("refreshViewsAccountAccessAndHolders.csbNewViewsForAccount.newViewForAccount start:-------" + newViewForAccount)
                view <- Views.views.vend.getOrCreateSystemViewFromCbs(newViewForAccount)//TODO, only support system views so far, may add custom views later.
                _ = UserRefreshes.UserRefreshes.vend.createOrUpdateRefreshUser(user.userId)
                view <- if (view.isSystem) //if the view is a system view, we will call `grantAccessToSystemView`
                  Views.views.vend.grantAccessToSystemView(bankAccountId.bankId, bankAccountId.accountId, view, user)
                else //otherwise, we will call `grantAccessToCustomView`
                  Views.views.vend.grantAccessToCustomView(view.uid, user)
                _ = logger.debug("refreshViewsAccountAccessAndHolders.csbNewViewsForAccount.newViewForAccount finish:-------" + newViewForAccount)
              }yield{
                view
              }
            }
          } yield {
            success
          }
        }
        true
      }
      else {
        false
      }
  }

  /*
          ┌────────────┐
          │FIND A USER │
          │AT MAPPER DB│
          └──────┬─────┘
      ___________▽___________                        ┌────────────────────────┐
     ╱        props:         ╲                       │FIND USER BY COMPOSITE  │
    ╱ local_identity_provider ╲______________________│KEY (username,          │
    ╲                         ╱yes                   │local_identity_provider)│
     ╲_______________________╱                       └────────────┬───────────┘
                 │no                                              │
              ___▽____     ┌────────────────────────┐             │
             ╱ props: ╲    │FIND USER BY COMPOSITE  │             │
            ╱ hostname ╲___│KEY (username, hostname)│             │
            ╲          ╱yes└────────────┬───────────┘             │
             ╲________╱                 │                         │
                 │no                    │                         │
              ┌──▽──┐                   │                         │
              │ERROR│                   │                         │
              └─────┘                   │                         │
                                        └──────┬──────────────────┘
                                          ┌────▽────┐
                                          │BOX[USER]│
                                          └─────────┘
  */
  /**
   * Find the Auth User by the composite key (username, provider).
   * Only search at the local database.
   * Please note that provider is implicitly defined i.e. not provided via a parameter
   */
  @deprecated("AuthUser unique key is username and provider, please use @findAuthUserByUsernameAndProvider instead.","06.06.2024")
  def findAuthUserByUsernameLocallyLegacy(name: String): Box[TheUserType] = {
    // 1st try is provider with local_identity_provider or hostname value
    find(By(this.username, name), By(this.provider, Constant.localIdentityProvider))
      // 2nd try is provider with null value
      .or(find(By(this.username, name), NullRef(this.provider)))
      // 3rd try is provider with empty string value
      .or(find(By(this.username, name), By(this.provider, "")))
  }

  def findAuthUserByUsernameAndProvider(name: String, provider: String): Box[TheUserType] = {
    find(By(this.username, name), By(this.provider, provider))
  }
  def findAuthUserByPrimaryKey(key: Long): Box[TheUserType] = {
    find(By(this.user, key))
  }

  def passwordResetUrl(name: String, email: String, userId: String): String = {
    find(By(this.username, name)) match {
      case Full(authUser) if authUser.validated_? && authUser.email == email =>
        Users.users.vend.getUserByUserId(userId) match {
          case Full(u) if u.name == name && u.emailAddress == email =>
            authUser.resetUniqueId().save
            val resetLink = Constant.HostName+
              passwordResetPath.mkString("/", "/", "/")+urlEncode(authUser.getUniqueId())
            logger.warn(s"Password reset url is created for this user: $email")
            // TODO Notify via email appropriate persons 
            resetLink
          case _ => ""
        }
        case _ => ""
    }
  }

  override def passwordResetXhtml = {
    <div id="recover-password" tabindex="-1">
      <h1>{if(ObpS.queryString.isDefined) Helper.i18n("set.your.password") else S.?("reset.your.password")}</h1>
      <form action={ObpS.uri} method="post">
        <div class="form-group">
          <label for="password">{S.?("enter.your.new.password")}</label> <span><input id="password" class="form-control" type="password" /></span>
        </div>
        <div class="form-group">
          <label for="repeatpassword">{S.?("repeat.your.new.password")}</label> <span><input id="repeatpassword" class="form-control" type="password" /></span>
        </div>
        <div class="form-group">
          <input type="submit" class="btn btn-danger" />
        </div>
      </form>
    </div>
  }
  
  /**
    * Find the authUsers by author email(authUser and resourceUser are the same).
    * Only search for the local database. 
    */
  protected def findUsersByEmailLocally(email: String): List[TheUserType] = {
    val usernames: List[String] = this.getResourceUsersByEmail(email).map(_.user.name)
    findAll(ByList(this.username, usernames))
  }
  def signupSubmitButtonValue() = getWebUiPropsValue("webui_signup_form_submit_button_value", S.?("sign.up"))

  //overridden to allow redirect to loginRedirect after signup. This is mostly to allow
  // loginFirst menu items to work if the user doesn't have an account. Without this,
  // if a user tries to access a logged-in only page, and then signs up, they don't get redirected
  // back to the proper page.
  override def signup = {
    val theUser: TheUserType = mutateUserOnSignup(createNewUserInstance())
    val theName = signUpPath.mkString("")

    //Check the internal redirect, in case for open redirect issue.
    // variable redir is from loginRedirect, it is set-up in OAuthAuthorisation.scala as following code:
    // val currentUrl = ObpS.uriAndQueryString.getOrElse("/")
    // AuthUser.loginRedirect.set(Full(Helpers.appendParams(currentUrl, List((LogUserOutParam, "false")))))
    val loginRedirectSave = loginRedirect.is

    def testSignup() {
      validateSignup(theUser) match {
        case Nil =>
          //here we check loginRedirectSave (different from implementation in super class)
          val redir = loginRedirectSave match {
            case Full(url) =>
              loginRedirect(Empty)
              url
            case _ =>
              //if the register page url (user_mgt/sign_up?after-signup=link-to-customer) contains the parameter 
              //after-signup=link-to-customer,then it will redirect to the on boarding customer page.
              ObpS.param("after-signup") match { 
                case url if (url.equals("link-to-customer")) =>
                  "/add-user-auth-context-update-request"
                case _ =>
                  homePage
            }
          }
          if (Helper.isValidInternalRedirectUrl(redir.toString)) {
            actionsAfterSignup(theUser, () => {
              S.redirectTo(redir)
            })
          } else {
            S.error(S.?(ErrorMessages.InvalidInternalRedirectUrl))
            logger.info(ErrorMessages.InvalidInternalRedirectUrl + loginRedirect.get)
          }

        case xs =>
          xs.foreach{
            e => S.error(e.field.uniqueFieldId.openOrThrowException("There is no uniqueFieldId."), e.msg)
          }
          signupFunc(Full(innerSignup _))
      }
    }

    def innerSignup = {
      val bind = "type=submit" #> signupSubmitButton(signupSubmitButtonValue(), testSignup _)
      bind(signupXhtml(theUser))
    }
    
    if(APIUtil.getPropsAsBoolValue("user_invitation.mandatory", false)) 
      S.redirectTo("/user-invitation-info") 
    else 
      innerSignup
  }

  def scrambleAuthUser(userPrimaryKey: UserPrimaryKey): Box[Boolean] = tryo {
    AuthUser.find(By(AuthUser.user, userPrimaryKey.value)) match {
      case Full(user) => 
        val scrambledUser = user.firstName(Helpers.randomString(16))
          .email(Helpers.randomString(10) + "@example.com")
          .username("DELETED-" + Helpers.randomString(16))
          .firstName(Helpers.randomString(16))
          .lastName(Helpers.randomString(16))
          .password(Helpers.randomString(40))
          .validated(false)
        scrambledUser.save
      case Empty => true // There is a resource user but no the correlated Auth user 
      case _ => false // Error case
    }
  }
  
}
