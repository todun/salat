/**
 * Copyright (c) 2010, 2011 Novus Partners, Inc. <http://novus.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For questions and comments about this product, please see the project page at:
 *
 * http://github.com/novus/salat
 *
 */
package com.novus.salat

import java.lang.reflect.Method
import scala.tools.scalap.scalax.rules.scalasig._
import com.mongodb.casbah.Imports._

import com.novus.salat.annotations.raw._
import com.novus.salat.annotations.util._
import com.novus.salat.util._
import com.mongodb.casbah.commons.Logging

class MissingPickledSig(clazz: Class[_]) extends Error("Failed to parse pickled Scala signature from: %s".format(clazz))
class MissingExpectedType(clazz: Class[_]) extends Error("Parsed pickled Scala signature, but no expected type found: %s"
                                                         .format(clazz))
class MissingTopLevelClass(clazz: Class[_]) extends Error("Parsed pickled scala signature but found no top level class for: %s"
                                                          .format(clazz))
class NestingGlitch(clazz: Class[_], owner: String, outer: String, inner: String) extends Error("Didn't find owner=%s, outer=%s, inner=%s in pickled scala sig for %s"
                                                                                                .format(owner, outer, inner, clazz))

abstract class Grater[X <: CaseClass](val clazz: Class[X])(implicit val ctx: Context) extends Logging {
  ctx.accept(this)

  protected def parseScalaSig: Option[ScalaSig] = {
    // TODO: provide a cogent explanation for the process that is going on here, document the fork logic on the wiki
    val firstPass = ScalaSigParser.parse(clazz)
    log.trace("parseScalaSig: FIRST PASS on %s\n%s", clazz, firstPass)
    firstPass match {
      case Some(x) => {
        log.trace("1")
        Some(x)
      }
      case None if clazz.getName.endsWith("$") => {
        log.trace("2")
        val clayy = Class.forName(clazz.getName.replaceFirst("\\$$", ""))
        val secondPass = ScalaSigParser.parse(clayy)
        log.trace("parseScalaSig: SECOND PASS on %s\n%s", clayy, secondPass)
        secondPass
      }
      case x => x
    }
  }

  protected lazy val sym = {
    val pss = parseScalaSig
    log.trace("parseScalaSig: clazz=%\n%s", clazz, pss)
    pss match {
      case Some(x) => {
        val topLevelClasses = x.topLevelClasses
        log.trace("parseScalaSig: found top-level classes %s", topLevelClasses)
        topLevelClasses.headOption match {
          case Some(tlc) => {
            log.trace("parseScalaSig: returning top-level class %s", tlc)
            tlc
          }
          case None => {
            val topLevelObjects = x.topLevelObjects
            log.trace("parseScalaSig: found top-level objects %s", topLevelObjects)
            topLevelObjects.headOption match {
              case Some(tlo) => {
                log.trace("parseScalaSig: returning top-level object %s", tlo)
                tlo
              }
              case _ => throw new MissingExpectedType(clazz)
            }
          }
        }
      }
      case None => throw new MissingPickledSig(clazz)
    }
  }

  protected lazy val indexedFields = {
    sym.children
    .filter(_.isCaseAccessor)
    .filter(!_.isPrivate)
    .map(_.asInstanceOf[MethodSymbol])
    .zipWithIndex
    .map {
      case (ms, idx) =>
        Field(idx, ms.name, typeRefType(ms), clazz.getMethod(ms.name))
    }
  }
  lazy val fields = collection.SortedMap.empty[String, Field] ++ indexedFields.map { f => f.name -> f }

  lazy val extraFieldsToPersist = clazz.getDeclaredMethods.toList
  .filter(_.isAnnotationPresent(classOf[Persist]))
  .filterNot(m => m.annotated_?[Ignore])

  lazy val companionClass = clazz.companionClass
  lazy val companionObject = clazz.companionObject


  protected lazy val constructor: Option[Method] = {
    log.trace("constructor: sym=%s (isModule=%s)", sym, sym.isModule)
    if (sym.isModule) {
      None
    }
    else {
      log.trace("companionClass: %s", companionClass)
      log.trace("methods: %s", companionClass.getMethods.map(_.getName).mkString(", "))
      companionClass.getMethods.filter(_.getName == "apply").headOption
    }
  }


  protected lazy val defaults: Seq[Option[Method]] = indexedFields.map {
    field => try {
      Some(companionClass.getMethod("apply$default$%d".format(field.idx + 1)))
    } catch {
      case _ => None
    }
  }

  protected def typeRefType(ms: MethodSymbol): TypeRefType = ms.infoType match {
    case PolyType(tr @ TypeRefType(_, _, _), _) => tr
  }

  def iterateOut[T](o: X)(f: ((String, Any)) => T): Iterator[T] = {
    val fromConstructor = o.productIterator.zip(indexedFields.iterator)
    val withPersist = extraFieldsToPersist.iterator.map {
      case m: Method => {
        val element = m.invoke(o)
        val field: com.novus.salat.Field = {
          sym
          .children
          .filter(f => f.name == m.getName && f.isAccessor)
          .map(_.asInstanceOf[MethodSymbol])
          .headOption match {
            case Some(ho) => com.novus.salat.Field(-1, ho.name, typeRefType(ho), m)
            case None => throw new RuntimeException("Could not find ScalaSig method symbol for method=%s in clazz=%s".format(m.getName, clazz.getName))
          }
        }

        (element, field)
      }
    }
    (fromConstructor ++ withPersist).map(outField).filter(_.isDefined).map(_.get).map(f)
  }

  type OutHandler = PartialFunction[(Any, Field), Option[(String, Any)]]
  protected def outField: OutHandler = {
    case (_, field) if field.ignore => None
    case (null, _) => None
    case (element, field) => {
      field.out_!(element) match {
        case Some(None) => None
        case Some(serialized) =>
          Some(ctx.keyOverrides.get(field.name).getOrElse(field.name) -> (serialized match {
            case Some(unwrapped) => unwrapped
            case _ => serialized
          }))
        case _ => None
      }
    }
  }

  def asDBObject(o: X): DBObject = {
    val builder = MongoDBObject.newBuilder
    ctx.typeHint match {
      case Some(hint) => builder += hint -> clazz.getName
      case _ =>
    }

    iterateOut(o) {
      case (key, value) => builder += key -> value
    }.toList

    builder.result
  }

  protected def safeDefault(field: Field) =
    defaults(field.idx).map(m => Some(m.invoke(companionObject))).getOrElse(None) match {
      case yes @ Some(default) => yes
      case _ => field.typeRefType match {
        case IsOption(_) => Some(None)
        case _ => throw new Exception("%s requires value for '%s'".format(clazz, field.name))
      }
    }

  def asObject(dbo: MongoDBObject): X =
    if (sym.isModule) {
      companionObject.asInstanceOf[X]
    }
    else {
      val args = indexedFields.map {
        case field if field.ignore => safeDefault(field)
        case field => dbo.get(ctx.keyOverrides.get(field.name).getOrElse(field.name)) match {
          case Some(value) => field.in_!(value)
          case _ => safeDefault(field)
        }
      }.map(_.get.asInstanceOf[AnyRef])
      constructor match {
        case Some(constructor) => try {
          constructor.invoke(companionObject, args: _*).asInstanceOf[X]
        }
        catch {
          case cause: Throwable => throw new ToObjectGlitch(this, sym, constructor, args, cause)
        }
        case None => throw new MissingConstructor(sym)
      }
    }

  override def toString = "Grater(%s @ %s)".format(clazz, ctx)

  override def equals(that: Any) = that.isInstanceOf[Grater[_]] && that.hashCode == this.hashCode

  override def hashCode = sym.path.hashCode
}

class MissingConstructor(sym: SymbolInfoSymbol) extends Error("Coudln't find a constructor for %s".format(sym.path))
class ToObjectGlitch[X<:CaseClass](grater: Grater[X], sym: SymbolInfoSymbol, constructor: Method, args: Seq[AnyRef], cause: Throwable) extends Error(
  """

  %s toObject failed on:
  SYM: %s
  CONSTRUCTOR: %s
  ARGS:
  %s

  """.format(grater.toString, sym.path, constructor, ArgsPrettyPrinter(args)), cause)
