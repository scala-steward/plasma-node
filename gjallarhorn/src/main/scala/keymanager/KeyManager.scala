package keymanager

import akka.actor.Actor
import keymanager.KeyFile.uuid
import scorex.crypto.hash.Blake2b256


//DOMAIN: KeyManager Actor
object KeyManagerActor {
  case class GenerateKeyFile(password: String, seed: Array[Byte] = Blake2b256(uuid), defaultKeyDir: String)
  case class UnlockKeyFile(publicKeyString: String, password: String)
  case class LockKeyFile(publicKeyString: String, password: String)
}

//DOMAIN: For custom instantiation of KeyManager Actor
object KeyManagerActorRef {
  def apply(var secrets: Set[PrivateKey25519], defaultKeyDir: String)(implicit actorsystem: ActorSystem, ec: ExecutionContext): ActorRef = actorsystem.actorOf(props(secrets, defaultKeyDir))
  def props(var secrets: Set[PrivateKey25519], defaultKeyDir: String)(implicit ec: ExecutionContext): Props = Props(new KeyManagerActor(secrets, defaultKeyDir))
}

class KeyManager extends Actor {

  import KeyManager._

  val keyManager = Keys(Set.empty, "")

  //Overload messaging, stateful necessary
  override def receive: Receive = {
    case GenerateKeyFile(password, seed, defaultKeyDir) => KeyFile.apply(password, seed, defaultKeyDir)
    case UnlockKeyFile(pubKeyString, password) => ???
    case LockKeyFile(pubKeyString, password) => ???
  }
}