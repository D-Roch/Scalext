package com.scalext.direct.dispatcher

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder

import play.api.libs.json.JsArray
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import com.scalext.direct.remoting.{RpcResult, Rpc}
import com.scalext.direct.controller.DefaultControllerFactory
import play.api.Play
import com.scalext.json.MapSerializer

/**
 * Default Dispatcher
 */
class StandardDispatcher(directClasses: Map[String, Class[_]]) extends Dispatcher {

  // Retrieve the global object
  lazy val apiClassFactory: ScalextClassBuilder = {
    try {
      import scala.reflect.runtime.universe
      import play.api.Play.current
      val runtimeMirror = universe.runtimeMirror(getClass.getClassLoader)
      val configuredGlobal = Play.configuration.getString("global").getOrElse("Global")
      val module = runtimeMirror.staticModule(configuredGlobal)
      val obj = runtimeMirror.reflectModule(module)
      obj.instance.asInstanceOf[ScalextClassBuilder]
    }
    catch {
      case e: Exception => new DefaultControllerFactory()
    }

  }

  lazy val classInstances = directClasses.map {
    case (name, cls) => name -> apiClassFactory.buildClass(cls)
  }

  val gson = new GsonBuilder()
    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
    .create()

  /**
   * Dispatch multiple requests
   */
  override def dispatch(rpcs: Seq[Rpc]) = {
    rpcs.map(dispatch)
  }

  /**
   * Dispatch a single RPC
   */
  override def dispatch(rpc: Rpc) = {

    val cls = directClasses(rpc.action)
    val methodInstance = cls.getDeclaredMethods.find(_.getName == rpc.method).get

    val methodParams = methodInstance.getParameterTypes
    var methodArgs = List[Any]()

    methodArgs = rpc.data match {
      case JsArray(elements) => {
        elements.zipWithIndex.foldLeft(List[Any]()) {
          case (current, (value, index)) => current :+ valueToParam(value, methodParams(index))
        }
      }

      case seq: Seq[_] =>
        seq.zipWithIndex.foldLeft(List[Any]()) {
          case (current, (value, index)) => current :+ valueToParam(value, methodParams(index))
        }
    }

    if (!directClasses.contains(rpc.action)) {
      throw new Exception(s"Action ${rpc.action} not found")
    }

    val methodResult = methodInstance.invoke(
      classInstances(rpc.action),
      methodArgs.asInstanceOf[Seq[Object]]: _*)

    RpcResult(rpc, methodResult)
  }

  /**
   * Value to param
   */
  def valueToParam(param: Any, paramType: Class[_]): Any = {
    param match {
      case map: Map[_, _] => map
      case jsval: JsValue => gson.fromJson(Json.stringify(jsval), paramType)
      case _ => param
    }
  }
}