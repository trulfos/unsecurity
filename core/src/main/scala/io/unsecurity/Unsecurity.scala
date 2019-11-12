package io
package unsecurity

import cats.data.NonEmptyList
import cats.effect.Sync
import io.unsecurity.hlinx.HLinx.HLinx
import io.unsecurity.hlinx.{ReversedTupled, SimpleLinx, TransformParams}
import no.scalabin.http4s.directives.Directive
import org.http4s.headers.Allow
import org.http4s.util.CaseInsensitiveString
import org.http4s.{DecodeFailure, MediaRange, MediaType, Method, Request, Response}
import shapeless.HList

abstract class Unsecurity[F[_]: Sync, RU, U] extends AbstractUnsecurity[F, U] with UnsecurityOps[F] {

  def sc: SecurityContext[F, RU, U]

  case class MySecured[C, W](
      key: List[SimpleLinx],
      pathMatcher: PathMatcher[Any],
      consumes: Set[MediaRange],
      methodMap: Map[Method, Any => Directive[F, C]],
      entityEncoder: W => ResponseDirective[F]
  ) extends Secured[C, W] {
    override def authorization(predicate: C => Boolean): Completable[C, W] = {
      MyCompletable(
        key = key,
        pathMatcher = pathMatcher,
        consumes = consumes,
        methodMap = methodMap.mapValues(
          a2dc =>
            a2dc.andThen(
              dc =>
                Directive.commit(
                  dc.filter(
                    c => predicate(c).orF(HttpProblem.forbidden("Forbidden").toResponseF)
                  ))
          )),
        entityEncoder = entityEncoder
      )
    }
    override def mapD[C2](f: C => Directive[F, C2]): Secured[C2, W] = {
      MySecured(
        key = key,
        pathMatcher = pathMatcher,
        consumes = consumes,
        methodMap = methodMap.mapValues { a2dc =>
          a2dc.andThen { dc =>
            dc.flatMap(c => f(c))
          }
        },
        entityEncoder = entityEncoder,
      )
    }
    override def map[C2](f: C => C2): Secured[C2, W] = {
      MySecured(
        key = key,
        pathMatcher = pathMatcher,
        consumes = consumes,
        methodMap = methodMap.mapValues { a2dc =>
          a2dc.andThen { dc =>
            dc.map(c => f(c))
          }
        },
        entityEncoder = entityEncoder,
      )
    }
    override def mapF[C2](f: C => F[C2]): Secured[C2, W] = {
      MySecured(
        key = key,
        pathMatcher = pathMatcher,
        consumes = consumes,
        methodMap = methodMap.mapValues { a2dc =>
          a2dc.andThen { dc =>
            dc.flatMap(c => f(c).successF)
          }
        },
        entityEncoder = entityEncoder,
      )
    }
    def noAuthorization: Completable[C, W] =
      MyCompletable(
        key = key,
        pathMatcher = pathMatcher,
        consumes = consumes,
        methodMap = methodMap,
        entityEncoder = entityEncoder
      )
  }

  override def secure[P <: HList, R, W, TUP, TUP2](endpoint: Endpoint[P, R, W])(
      implicit reversedTupled: ReversedTupled.Aux[P, TUP],
      transformParams: TransformParams.Aux[TUP, (R, U), TUP2]
  ): Secured[TUP2, W] = {
    MySecured[TUP2, W](
      key = endpoint.path.toSimple.reverse,
      pathMatcher = createPathMatcher(endpoint.path).asInstanceOf[PathMatcher[Any]],
      consumes = endpoint.accepts.consumes,
      methodMap = Map(
        endpoint.method -> { tup: TUP =>
          val checkXsrfOrNothing: Directive[F, String] =
            if (endpoint.method == Method.PUT ||
                endpoint.method == Method.POST ||
                endpoint.method == Method.DELETE) {
              sc.xsrfCheck
            } else {
              Directive.success("xsrf not checked")
            }

          for {
            _       <- checkXsrfOrNothing
            rawUser <- sc.authenticate
            user <- Directive.commit(
                     Directive.getOrElseF(
                       sc.transformUser(rawUser),
                       HttpProblem.unauthorized("Unauthorized").toResponseF[F]
                     )
                   )
            r <- request.bodyAs[R] { error: DecodeFailure =>
                  HttpProblem.handleError(error).toResponse[F]
                }(endpoint.accepts, Sync[F])
          } yield {
            transformParams(tup, (r, user))
          }
        }.asInstanceOf[Any => Directive[F, TUP2]]
      ),
      entityEncoder = endpoint.produces
    )
  }

  override def unsecure[P <: HList, R, W, TUP, TUP2](endpoint: Endpoint[P, R, W])(
      implicit revGen: ReversedTupled.Aux[P, TUP],
      transformParam: TransformParams.Aux[TUP, Tuple1[R], TUP2]
  ): Completable[TUP2, W] = {
    MyCompletable[TUP2, W](
      key = endpoint.path.toSimple.reverse,
      pathMatcher = createPathMatcher[P, TUP](endpoint.path).asInstanceOf[PathMatcher[Any]],
      consumes = endpoint.accepts.consumes,
      methodMap = Map(
        endpoint.method -> { tup: TUP =>
          for {
            r <- request.bodyAs[R] { error: DecodeFailure =>
                  HttpProblem.handleError(error).toResponse[F]
                }(endpoint.accepts, Sync[F])
          } yield {
            transformParam(tup, Tuple1(r))
          }
        }.asInstanceOf[Any => Directive[F, TUP2]]
      ),
      entityEncoder = endpoint.produces
    )
  }

  case class MyCompletable[C, W](
      key: List[SimpleLinx],
      pathMatcher: PathMatcher[Any],
      consumes: Set[MediaRange],
      methodMap: Map[Method, Any => Directive[F, C]],
      entityEncoder: W => ResponseDirective[F]
  ) extends Completable[C, W] {
    override def run(f: C => W): Complete = {
      MyComplete(
        key = key,
        pathMatcher = pathMatcher,
        consumes = consumes,
        methodMap = methodMap.mapValues { a2dc =>
          a2dc.andThen { dc =>
            for {
              c <- dc
              w <- entityEncoder(f(c))
            } yield {
              w
            }
          }
        }
      )
    }

    override def map[C2](f: C => C2): Completable[C2, W] = {
      MyCompletable(
        key = key,
        pathMatcher = pathMatcher,
        consumes = consumes,
        methodMap = methodMap.mapValues { a2dc =>
          a2dc.andThen { dc =>
            dc.map(c => f(c))
          }
        },
        entityEncoder = entityEncoder
      )
    }

    override def mapD[C2](f: C => Directive[F, C2]): Completable[C2, W] = {
      MyCompletable(
        key = key,
        pathMatcher = pathMatcher,
        consumes = consumes,
        methodMap = methodMap.mapValues { a2dc =>
          a2dc.andThen { dc =>
            dc.flatMap(c => f(c))
          }
        },
        entityEncoder = entityEncoder
      )
    }

    override def mapF[C2](f: C => F[C2]): Completable[C2, W] = {
      MyCompletable(
        key = key,
        pathMatcher = pathMatcher,
        consumes = consumes,
        methodMap = methodMap.mapValues { a2dc =>
          a2dc.andThen { dc =>
            dc.flatMap(c => f(c).successF)
          }
        },
        entityEncoder = entityEncoder
      )
    }
  }

  case class MyComplete(
      key: List[SimpleLinx],
      pathMatcher: PathMatcher[Any],
      consumes: Set[MediaRange],
      methodMap: Map[Method, Any => ResponseDirective[F]]
  ) extends Complete {
    override def merge(other: AbstractUnsecurity[F, U]#Complete): AbstractUnsecurity[F, U]#Complete = {
      this.copy(
        methodMap = this.methodMap ++ other.methodMap,
        consumes = this.consumes ++ other.consumes
      )
    }
    override def compile: PathMatcher[Response[F]] = {
      def allow(methods: Set[Method]): Allow = Allow(NonEmptyList.fromListUnsafe(methods.toList))

      val f: PathMatcher[Response[F]] = pathMatcher.andThen { pathParamsDirective =>
        for {
          req        <- Directive.request
          pathParams <- pathParamsDirective
          res <- if (methodMap.isDefinedAt(req.method)) methodMap(req.method)(pathParams)
                else
                  Directive.error(
                    HttpProblem
                      .methodNotAllowed("Method not allowed", methodMap.keySet)
                      .toResponse
                      .putHeaders(allow(methodMap.keySet)))
          _ <- Unsecurity
                .validateContentType(req, consumes)
                .fold(problem => problem.toDirectiveError, _ => Directive.success(res))
        } yield {
          res
        }
      }
      f
    }
  }

  def createPathMatcher[PathParams <: HList, TUP](route: HLinx[PathParams])(
      implicit revTup: ReversedTupled.Aux[PathParams, TUP]): PathMatcher[TUP] =
    new PartialFunction[String, Directive[F, TUP]] {
      override def isDefinedAt(x: String): Boolean = {
        if (route.capture(x).isDefined) {
          log.trace(s"""'$x' did match /${route.toSimple.reverse.mkString("/")}""")
          true
        } else {
          log.trace(s"""'$x' did not match /${route.toSimple.reverse.mkString("/")}""")
          false
        }
      }

      override def apply(v1: String): Directive[F, TUP] = {
        val simpleRoute = route.toSimple.reverse.mkString("/", "/", "")
        log.trace(s"""Match: "$v1" = $simpleRoute""")
        val value: Either[String, TUP] = route.capture(v1).get

        value match {
          case Left(errorMsg) =>
            log.error(s"""Error converting "$v1" = $simpleRoute: $errorMsg""")

            Directive.failure(
              HttpProblem.badRequest("Bad Request", Some(errorMsg)).toResponse
            )

          case Right(params) =>
            Directive.success(params)

        }
      }
    }
}

object Unsecurity {
  def validateContentType[F[_]](request: Request[F], consumes: Set[MediaRange]): Either[HttpProblem, MediaRange] =
    for {
      contentTypeString <- request.headers
                            .get(CaseInsensitiveString("content-type"))
                            .toRight(HttpProblem.unsupportedMediaType("Content-Type missing", consumes))
      mediaType <- MediaType
                    .parse(contentTypeString.value)
                    .left
                    .map(pf => HttpProblem.unsupportedMediaType(s"Invalid Media-Type", consumes))
      supportedRange <- consumes
                         .find(mediaType.satisfies(_))
                         .toRight(HttpProblem.unsupportedMediaType(s"Content-Type not supported", consumes))
    } yield supportedRange
}
