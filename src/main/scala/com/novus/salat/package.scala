package com.novus

import com.mongodb.casbah.Imports._

import java.math.BigInteger
import com.novus.salat.{Grater, Context}

package object salat {

  type CaseClass = AnyRef with Product
  val TypeHint = "_typeHint"

  def timeAndLog[T](f: => T)(l: Long => Unit): T = {
    val t = System.currentTimeMillis
    val r = f
    l.apply(System.currentTimeMillis - t)
    r
  }

  def grater[X <: CaseClass](implicit ctx: Context, m: Manifest[X]): Grater[X] = ctx.lookup_![X](m)

  protected[salat] def getClassNamed(c: String)(implicit classLoaders: Seq[ClassLoader]): Option[Class[_]] = {
    try {
      var clazz: Class[_] = null
      val iter = classLoaders.iterator
      while (clazz == null && iter.hasNext) {
        try {
          clazz = Class.forName(c, true, iter.next)
        }
        catch {
          case e: ClassNotFoundException => // keep going, maybe it's in the next one
        }
      }

      if (clazz != null) Some(clazz) else None
    }
    catch {
      case _ => None
    }
  }

  protected[salat] def getCaseClass(c: String)(implicit classLoaders: Seq[ClassLoader]): Option[Class[CaseClass]] =
    getClassNamed(c).map(_.asInstanceOf[Class[CaseClass]])

  implicit def shortenOID(oid: ObjectId) = new {
    def asShortString = (new BigInteger(oid.toString, 16)).toString(36)
  }

  implicit def explodeOID(oid: String) = new {
    def asObjectId = new ObjectId((new BigInteger(oid, 36)).toString(16))
  }

  implicit def class2companion(clazz: Class[_]) = new {
    def companionClass: Class[_] =
      Class.forName(if (clazz.getName.endsWith("$")) clazz.getName else "%s$".format(clazz.getName))

    def companionObject = companionClass.getField("MODULE$").get(null)
  }
}