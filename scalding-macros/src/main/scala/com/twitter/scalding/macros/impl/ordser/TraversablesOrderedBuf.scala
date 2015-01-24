/*
 Copyright 2014 Twitter, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.twitter.scalding.macros.impl.ordser

import scala.language.experimental.macros
import scala.reflect.macros.Context
import java.io.InputStream

import com.twitter.scalding._
import com.twitter.scalding.serialization.OrderedSerialization
import scala.reflect.ClassTag

sealed trait ShouldSort
case object DoSort extends ShouldSort
case object NoSort extends ShouldSort

sealed trait MaybeArray
case object IsArray extends MaybeArray
case object NotArray extends MaybeArray

object TraversableCompare {
  import com.twitter.scalding.serialization.JavaStreamEnrichments._

  final def rawCompare(inputStreamA: InputStream, inputStreamB: InputStream)(consume: (InputStream, InputStream) => Int): Int = {
    val lenA = inputStreamA.readSize
    val lenB = inputStreamB.readSize

    val minLen = _root_.scala.math.min(lenA, lenB)
    var incr = 0
    var curIncr = 0
    while (incr < minLen && curIncr == 0) {
      curIncr = consume(inputStreamA, inputStreamB)
      incr = incr + 1
    }

    if (curIncr != 0) {
      curIncr
    } else {
      if (lenA < lenB) {
        -1
      } else if (lenA > lenB) {
        1
      } else {
        0
      }
    }
  }

  final def sharedMemCompare[T](iteratorA: Iterator[T], lenA: Int, iteratorB: Iterator[T], lenB: Int)(cmp: (T, T) => Int): Int = {
    val minLen: Int = _root_.scala.math.min(lenA, lenB)
    var incr: Int = 0
    var curIncr: Int = 0
    while (incr < minLen && curIncr == 0) {
      curIncr = cmp(iteratorA.next, iteratorB.next)
      incr = incr + 1
    }

    if (curIncr != 0) {
      curIncr
    } else {
      if (lenA < lenB) {
        -1
      } else if (lenA > lenB) {
        1
      } else {
        0
      }
    }
  }

  final def memCompareWithSort[T: ClassTag](travA: TraversableOnce[T], travB: TraversableOnce[T])(compare: (T, T) => Int): Int = {
    val iteratorA: Iterator[T] = travA.toArray.sortWith { (a: T, b: T) =>
      compare(a, b) < 0
    }.toIterator

    val iteratorB: Iterator[T] = travB.toArray.sortWith { (a: T, b: T) =>
      compare(a, b) < 0
    }.toIterator

    val lenA = travA.size
    val lenB = travB.size
    sharedMemCompare(iteratorA, lenA, iteratorB, lenB)(compare)
  }

  final def memCompare[T: ClassTag](travA: TraversableOnce[T], travB: TraversableOnce[T])(compare: (T, T) => Int): Int = {
    val lenA = travA.size
    val lenB = travB.size
    sharedMemCompare(travA.toIterator, lenA, travB.toIterator, lenB)(compare)
  }
}

object TraversablesOrderedBuf {
  def dispatch(c: Context)(buildDispatcher: => PartialFunction[c.Type, TreeOrderedBuf[c.type]]): PartialFunction[c.Type, TreeOrderedBuf[c.type]] = {
    case tpe if tpe.erasure =:= c.universe.typeOf[List[Any]] => TraversablesOrderedBuf(c)(buildDispatcher, tpe, NoSort, NotArray)
    case tpe if tpe.erasure =:= c.universe.typeOf[Seq[Any]] => TraversablesOrderedBuf(c)(buildDispatcher, tpe, NoSort, NotArray)
    case tpe if tpe.erasure =:= c.universe.typeOf[Vector[Any]] => TraversablesOrderedBuf(c)(buildDispatcher, tpe, NoSort, NotArray)
    // Arrays are special in that the erasure doesn't do anything
    case tpe if tpe.typeSymbol == c.universe.typeOf[Array[Any]].typeSymbol => TraversablesOrderedBuf(c)(buildDispatcher, tpe, NoSort, IsArray)
    // The erasure of a non-covariant is Set[_], so we need that here for sets
    case tpe if tpe.erasure =:= c.universe.typeOf[Set[Any]].erasure => TraversablesOrderedBuf(c)(buildDispatcher, tpe, DoSort, NotArray)
    case tpe if tpe.erasure =:= c.universe.typeOf[scala.collection.Set[Any]].erasure => TraversablesOrderedBuf(c)(buildDispatcher, tpe, DoSort, NotArray)
    case tpe if tpe.erasure =:= c.universe.typeOf[scala.collection.mutable.Set[Any]].erasure => TraversablesOrderedBuf(c)(buildDispatcher, tpe, DoSort, NotArray)

    case tpe if tpe.erasure =:= c.universe.typeOf[Map[Any, Any]].erasure => TraversablesOrderedBuf(c)(buildDispatcher, tpe, DoSort, NotArray)
    case tpe if tpe.erasure =:= c.universe.typeOf[scala.collection.Map[Any, Any]].erasure => TraversablesOrderedBuf(c)(buildDispatcher, tpe, DoSort, NotArray)
    case tpe if tpe.erasure =:= c.universe.typeOf[scala.collection.mutable.Map[Any, Any]].erasure => TraversablesOrderedBuf(c)(buildDispatcher, tpe, DoSort, NotArray)
  }

  def apply(c: Context)(buildDispatcher: => PartialFunction[c.Type, TreeOrderedBuf[c.type]],
    outerType: c.Type,
    maybeSort: ShouldSort,
    maybeArray: MaybeArray): TreeOrderedBuf[c.type] = {

    import c.universe._
    def freshT(id: String) = newTermName(c.fresh(s"fresh_$id"))

    val dispatcher = buildDispatcher

    val companionSymbol = outerType.typeSymbol.companionSymbol

    // When dealing with a map we have 2 type args, and need to generate the tuple type
    // it would correspond to if we .toList the Map.
    val innerType = if (outerType.asInstanceOf[TypeRefApi].args.size == 2) {
      val (tpe1, tpe2) = (outerType.asInstanceOf[TypeRefApi].args(0), outerType.asInstanceOf[TypeRefApi].args(1))
      val containerType = typeOf[Tuple2[Any, Any]].asInstanceOf[TypeRef]
      import compat._
      TypeRef.apply(containerType.pre, containerType.sym, List(tpe1, tpe2))
    } else {
      outerType.asInstanceOf[TypeRefApi].args.head
    }

    val innerTypes = outerType.asInstanceOf[TypeRefApi].args

    val innerBuf: TreeOrderedBuf[c.type] = dispatcher(innerType)

    new TreeOrderedBuf[c.type] {
      override val ctx: c.type = c
      override val tpe = outerType
      override def compareBinary(inputStreamA: ctx.TermName, inputStreamB: ctx.TermName) = {
        val innerCompareFn = freshT("innerCompareFn")
        val a = freshT("a")
        val b = freshT("b")
        q"""
        def $innerCompareFn(a: InputStream, b: InputStream) = {
          val $a = a
          val $b = b
          ${innerBuf.compareBinary(a, b)}
        }
        _root_.com.twitter.scalding.macros.impl.ordser.TraversableCompare.rawCompare($inputStreamA, $inputStreamB)($innerCompareFn)
      """
      }

      override def put(inputStream: ctx.TermName, element: ctx.TermName) = {
        val bytes = freshT("bytes")
        val len = freshT("len")
        val innerElement = freshT("innerElement")
        val cmpRes = freshT("cmpRes")
        maybeSort match {
          case DoSort =>
            q"""
          val $len = $element.size
          $inputStream.writeSize($len)

          if($len > 0) {
            $element.toArray.sortWith { (a, b) =>
                val $cmpRes = ${innerBuf.compare(newTermName("a"), newTermName("b"))}
                $cmpRes < 0
            }.foreach{ case $innerElement =>
              ${innerBuf.put(inputStream, innerElement)}
            }
          }
        """
          case NoSort =>
            q"""
        val $len: Int = $element.size
        $inputStream.writeSize($len)
        $element.foreach { case $innerElement =>
            ${innerBuf.put(inputStream, innerElement)}
        }
        """
        }

      }
      override def hash(element: ctx.TermName): ctx.Tree = q"$element.hashCode"

      override def get(inputStream: ctx.TermName): ctx.Tree = {
        val len = freshT("len")
        val firstVal = freshT("firstVal")
        val travBuilder = freshT("travBuilder")
        val iter = freshT("iter")
        val extractionTree = maybeArray match {
          case IsArray =>
            q"""val $travBuilder = new Array[..$innerTypes]($len)
            var $iter = 0
            while($iter < $len) {
              $travBuilder($iter) = ${innerBuf.get(inputStream)}
              $iter = $iter + 1
            }
            $travBuilder : $outerType
            """
          case NotArray =>
            q"""val $travBuilder = $companionSymbol.newBuilder[..$innerTypes]
            var $iter = 0
            while($iter < $len) {
              $travBuilder += ${innerBuf.get(inputStream)}
              $iter = $iter + 1
            }
            $travBuilder.result : $outerType
            """
        }
        q"""
        val $len: Int = $inputStream.readSize
        if($len > 0)
        {
          if($len == 1) {
            val $firstVal: $innerType = ${innerBuf.get(inputStream)}
            $companionSymbol.apply($firstVal) : $outerType
          } else {
            $extractionTree : $outerType
          }
        } else {
          $companionSymbol.empty : $outerType
        }
      """
      }

      override def compare(elementA: ctx.TermName, elementB: ctx.TermName): ctx.Tree = {

        val a = freshT("a")
        val b = freshT("b")
        val cmpFnName = freshT("cmpFnName")
        val innerCmpFn = q"""
          def $cmpFnName(a: $innerType, b: $innerType): Int = {
            val $a = a
            val $b = b
            ${innerBuf.compare(a, b)}
          }
          """
        maybeSort match {
          case DoSort =>
            q"""
              $innerCmpFn
              _root_.com.twitter.scalding.macros.impl.ordser.TraversableCompare.memCompareWithSort($elementA, $elementB)($cmpFnName)
              """

          case NoSort =>
            q"""
              $innerCmpFn
              _root_.com.twitter.scalding.macros.impl.ordser.TraversableCompare.memCompare($elementA, $elementB)($cmpFnName)
              """
        }

      }

      override val lazyOuterVariables: Map[String, ctx.Tree] = innerBuf.lazyOuterVariables

      override def length(element: Tree): LengthTypes[c.type] = {

        innerBuf.length(q"$element.head") match {
          case const: ConstantLengthCalculation[_] =>
            FastLengthCalculation(c)(q"""{
              sizeBytes($element.size) + $element.size * ${const.toInt}
            }""")
          case m: MaybeLengthCalculation[_] =>
            val maybeRes = freshT("maybeRes")
            MaybeLengthCalculation(c)(q"""
              if($element.isEmpty) {
                _root_.com.twitter.scalding.macros.impl.ordser.DynamicLen(1)
              } else {
              val maybeRes = ${m.asInstanceOf[MaybeLengthCalculation[c.type]].t}
              maybeRes match {
                case _root_.com.twitter.scalding.macros.impl.ordser.ConstLen(constSize) =>
                  val sizeOverhead = sizeBytes($element.size)
                  _root_.com.twitter.scalding.macros.impl.ordser.DynamicLen(constSize * $element.size + sizeOverhead)

                  // todo maybe we should support this case
                  // where we can visit every member of the list relatively fast to ask
                  // its length. Should we care about sizes instead maybe?
                case _root_.com.twitter.scalding.macros.impl.ordser.DynamicLen(_) =>
                   _root_.com.twitter.scalding.macros.impl.ordser.NoLengthCalculation
                case _ => _root_.com.twitter.scalding.macros.impl.ordser.NoLengthCalculation
              }
            }
            """)
          // Something we can't workout the size of ahead of time
          case _ => MaybeLengthCalculation(c)(q"""
              if($element.isEmpty) {
                _root_.com.twitter.scalding.macros.impl.ordser.DynamicLen(1)
              } else {
                _root_.com.twitter.scalding.macros.impl.ordser.NoLengthCalculation
              }
            """)
        }
      }
    }
  }
}

