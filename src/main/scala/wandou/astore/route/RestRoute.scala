package wandou.astore.route

import akka.actor.ActorSystem
import akka.contrib.pattern.ClusterSharding
import akka.pattern.ask
import akka.util.Timeout
import org.apache.avro.Schema
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import spray.http.StatusCodes
import spray.routing.Directives
import wandou.astore.schema.DelSchema
import wandou.astore.schema.PutSchema
import wandou.astore.schema.SchemaBoard
import wandou.astore.script.DelScript
import wandou.astore.script.PutScript
import wandou.astore.script.ScriptBoard
import wandou.avpath
import wandou.avpath.Evaluator.Ctx
import wandou.avro
import wandou.avro.ToJson

trait RestRoute { _: spray.routing.Directives =>
  def system: ActorSystem
  def readTimeout: Timeout
  def writeTimeout: Timeout

  implicit def executionContext: ExecutionContext = system.dispatcher

  val scriptBoard = ScriptBoard(system).scriptBoard
  val schemaBoard = SchemaBoard(system).schemaBoard
  val schemaParser = new Schema.Parser()

  final def resolver(entityName: String) = ClusterSharding(system).shardRegion(entityName)

  final def restApi = schemaApi ~ accessApi

  final def schemaApi = {
    path("putschema" / Rest) { entityName =>
      post {
        parameters('fname.as[String]) { fname =>
          entity(as[String]) { schemaStr =>
            try {
              val schema = schemaParser.parse(schemaStr)
              if (schema.getType == Schema.Type.UNION) {
                val entitySchema = schema.getTypes.get(schema.getIndexNamed(fname))
                if (entitySchema != null) {
                  complete {
                    schemaBoard.ask(PutSchema(entityName, entitySchema))(writeTimeout).collect {
                      case Success(_)  => StatusCodes.OK
                      case Failure(ex) => StatusCodes.InternalServerError
                    }
                  }
                } else {
                  complete(StatusCodes.BadRequest)
                }
              } else {
                complete(StatusCodes.BadRequest)
              }

            } catch {
              case ex: Throwable =>
                println(ex) // todo
                complete(StatusCodes.BadRequest)
            }
          }
        } ~ entity(as[String]) { schemaStr =>
          try {
            val schema = schemaParser.parse(schemaStr)
            complete {
              schemaBoard.ask(PutSchema(entityName, schema))(writeTimeout).collect {
                case Success(_)  => StatusCodes.OK
                case Failure(ex) => StatusCodes.InternalServerError
              }
            }
          } catch {
            case ex: Throwable =>
              println(ex) // todo
              complete(StatusCodes.BadRequest)
          }
        }
      }
    } ~ path("delschema" / Rest) { entityName =>
      get {
        complete {
          schemaBoard.ask(DelSchema(entityName))(writeTimeout).collect {
            case Success(_)  => StatusCodes.OK
            case Failure(ex) => StatusCodes.InternalServerError
          }
        }
      }
    }
  }

  final def accessApi = {
    pathPrefix(Segment) { entityName =>
      path("get" / Rest) { id =>
        get {
          parameters('field.as[String]) { fieldName =>
            SchemaBoard.schemaOf(entityName) match {
              case Some(schema) =>
                val field = schema.getField(fieldName)
                if (field != null) {
                  complete {
                    resolver(entityName).ask(avpath.GetField(id, fieldName))(readTimeout).collect {
                      case value =>
                        ToJson.toAvroJsonString(value, field.schema)
                      //avro.avroEncode(value, field.schema) match {
                      //  case Success(bytes) => bytes
                      //  case _              => Array[Byte]()
                      //}
                    }
                  }
                } else {
                  complete(StatusCodes.BadRequest)
                }
              case None =>
                complete(StatusCodes.BadRequest)
            }
          } ~ {
            SchemaBoard.schemaOf(entityName) match {
              case Some(schema) =>
                complete {
                  resolver(entityName).ask(avpath.GetRecord(id))(readTimeout).collect {
                    case value =>
                      ToJson.toAvroJsonString(value, schema)
                    //avro.avroEncode(value, schema) match {
                    //  case Success(bytes) => bytes
                    //  case _              => Array[Byte]()
                    //}
                  }
                }
              case None =>
                complete(StatusCodes.BadRequest)
            }
          }
        }
      } ~ path("put" / Rest) { id =>
        post {
          parameter('field.as[String]) { fieldName =>
            entity(as[String]) { json =>
              complete {
                resolver(entityName).ask(avpath.PutFieldJson(id, fieldName, json))(writeTimeout).collect {
                  case Success(_)  => StatusCodes.OK
                  case Failure(ex) => StatusCodes.InternalServerError
                }
              }
            }
          } ~ {
            entity(as[String]) { json =>
              complete {
                resolver(entityName).ask(avpath.PutRecordJson(id, json))(writeTimeout).collect {
                  case Success(_)  => StatusCodes.OK
                  case Failure(ex) => StatusCodes.InternalServerError
                }
              }
            }
          }
        }
      } ~ path("select" / Rest) { id =>
        post {
          entity(as[String]) { body =>
            splitPathAndValue(body) match {
              case List(pathExp, _*) =>
                complete {
                  resolver(entityName).ask(avpath.Select(id, pathExp))(readTimeout).collect {
                    case xs: List[_] =>
                      xs.map {
                        case Ctx(value, schema, _, _) => ToJson.toAvroJsonString(value, schema)
                      } mkString ("[", ",", "]")
                  }
                }
              case _ =>
                complete(StatusCodes.BadRequest)
            }
          }
        }
      } ~ path("update" / Rest) { id =>
        post {
          entity(as[String]) { body =>
            splitPathAndValue(body) match {
              case List(pathExp, valueJson) =>
                complete {
                  resolver(entityName).ask(avpath.UpdateJson(id, pathExp, valueJson))(writeTimeout).collect {
                    case Success(_)  => StatusCodes.OK
                    case Failure(ex) => StatusCodes.InternalServerError
                  }
                }
              case _ =>
                complete(StatusCodes.BadRequest)
            }
          }
        }
      } ~ path("insert" / Rest) { id =>
        post {
          entity(as[String]) { body =>
            splitPathAndValue(body) match {
              case List(pathExp, json) =>
                complete {
                  resolver(entityName).ask(avpath.InsertJson(id, pathExp, json))(writeTimeout).collect {
                    case Success(_)  => StatusCodes.OK
                    case Failure(ex) => StatusCodes.InternalServerError
                  }
                }
              case _ =>
                complete(StatusCodes.BadRequest)
            }
          }
        }
      } ~ path("insertall" / Rest) { id =>
        post {
          entity(as[String]) { body =>
            splitPathAndValue(body) match {
              case List(pathExp, json) =>
                complete {
                  resolver(entityName).ask(avpath.InsertAllJson(id, pathExp, json))(writeTimeout).collect {
                    case Success(_)  => StatusCodes.OK
                    case Failure(ex) => StatusCodes.InternalServerError
                  }
                }
              case _ =>
                complete(StatusCodes.BadRequest)
            }
          }
        }
      } ~ path("delete" / Rest) { id =>
        post {
          entity(as[String]) { body =>
            splitPathAndValue(body) match {
              case List(pathExp, _*) =>
                complete {
                  resolver(entityName).ask(avpath.Delete(id, pathExp))(writeTimeout).collect {
                    case Success(_)  => StatusCodes.OK
                    case Failure(ex) => StatusCodes.InternalServerError
                  }
                }
              case _ =>
                complete(StatusCodes.BadRequest)
            }
          }
        }
      } ~ path("clear" / Rest) { id =>
        post {
          entity(as[String]) { body =>
            splitPathAndValue(body) match {
              case List(pathExp, _*) =>
                complete {
                  resolver(entityName).ask(avpath.Clear(id, pathExp))(writeTimeout).collect {
                    case Success(_)  => StatusCodes.OK
                    case Failure(ex) => StatusCodes.InternalServerError
                  }
                }
              case _ =>
                complete(StatusCodes.BadRequest)
            }
          }
        }
      } ~ path("putscript" / Rest) { scriptId =>
        post {
          entity(as[String]) { script =>
            complete {
              scriptBoard.ask(PutScript("Account", scriptId, script))(writeTimeout).collect {
                case Success(_)  => StatusCodes.OK
                case Failure(ex) => StatusCodes.InternalServerError
              }
            }
          }
        }
      } ~ path("delscript" / Rest) { scriptId =>
        post {
          entity(as[String]) { script =>
            complete {
              scriptBoard.ask(DelScript("Account", scriptId))(writeTimeout).collect {
                case Success(_)  => StatusCodes.OK
                case Failure(ex) => StatusCodes.InternalServerError
              }
            }
          }
        }
      }
    }
  }

  private def splitPathAndValue(body: String): List[String] = {
    val len = body.length
    var i = body.indexOf('\r')
    if (i > 0) {
      if (i + 1 < len && body.charAt(i + 1) == '\n') {
        val pathExp = body.substring(0, i)
        val valueJson = body.substring(i + 2, len)
        List(pathExp, valueJson)
      } else {
        val pathExp = body.substring(0, i)
        val valueJson = body.substring(i + 1, len)
        List(pathExp, valueJson)
      }
    } else {
      i = body.indexOf('\n')
      if (i > 0) {
        val pathExp = body.substring(0, i)
        val valueJson = body.substring(i + 1, len)
        List(pathExp, valueJson)
      } else {
        List(body)
      }
    }
  }

}
