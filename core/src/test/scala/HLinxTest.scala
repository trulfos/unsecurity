import io.unsecurity.hlinx.HLinx._
import shapeless.{::, HNil}
import org.scalatest.funspec.AnyFunSpec

class HLinxTest extends AnyFunSpec {

  describe("capture") {
    describe("StaticFragment") {
      describe("Root / foo / bar") {
        val link = Root / "foo" / "bar"

        it("should match equal path") {
          assert(link.capture("/foo/bar").isDefined)
        }

        it("should not match shorter path") {
          assert(link.capture("/foo") === None)
        }

        it("should not match different path") {
          assert(link.capture("/foo/baz") === None)
        }

        it("should not match longer path") {
          assert(link.capture("/foo/bar/baz") === None)
        }
      }
    }

    describe("VarFragment") {
      describe("with one param") {
        val link = Root / "foo" / param[String]("str") / "bar"

        it("should not match shorter path") {
          assert(link.capture("/foo") === None)
        }

        it("should not match longer path") {
          assert(link.capture("/foo/str/bar/longer") === None)
        }

        it("should not match different path") {
          assert(link.capture("/bar/str/foo") === None)
        }

        it("should not match if static part of varfragment doesn't match") {
          assert(link.capture("/foo/str") === None)
        }

        it("should match equal path") {
          assert(link.capture("/foo/str/bar") === Some(Right(Tuple1("str"))))
        }
      }

      describe("with two params") {
        val link =
          Root / "foo" / param[String]("str") / param[Int]("int") / "bar"

        it("should not match shorter path") {
          assert(link.capture("/foo") === None)
        }

        it("should not match if static in varfragment doesn't match") {
          assert(link.capture("/foo/str/1/baz") === None)
        }

        it("should match equal path") {
          assert(link.capture("/foo/str/1/bar") === Some(Right(("str", 1))))
        }
      }
    }

    describe("VarParam") {
      describe("with one param") {
        val link = Root / "foo" / "bar" :? param[String]("str")

        it("should not match shorter path") {
          assert(link.capture("/foo") === None)
        }

        it("should not match longer path") {
          assert(link.capture("/foo/bar/longer") === None)
        }

        it("should not match different path") {
          assert(link.capture("/bar/str/foo") === None)
        }

        it("should not match if static part of varfragment doesn't match") {
          assert(link.capture("/foo/bar") === None)
        }

        it("should match equal path") {
          assert(link.capture("/foo/bar?str=p1") === Some(Right(Tuple1("p1"))))
        }
      }

      describe("with one optional param") {
        val link = Root / "foo" / "bar" :? "str".as[String].?

        it("should not match shorter path") {
          assert(link.capture("/foo") === None)
        }

        it("should not match longer path") {
          assert(link.capture("/foo/bar/longer") === None)
        }

        it("should not match different path") {
          assert(link.capture("/bar/str/foo") === None)
        }

        it("should not match if static part of varfragment doesn't match") {
          assert(link.capture("/foo/str") === None)
        }

        it("should match equal path") {
          assert(link.capture("/foo/bar?str=p1") === Some(Right(Tuple1(Some("p1")))))
        }
      }

      describe("with list of params") {
        val link = Root / "foo" / "bar" :? "str".as[String].*

        it("should not match shorter path") {
          assert(link.capture("/foo") === None)
        }

        it("should not match longer path") {
          assert(link.capture("/foo/bar/longer") === None)
        }

        it("should not match different path") {
          assert(link.capture("/bar/str/foo") === None)
        }

        it("should not match if static part of varfragment doesn't match") {
          assert(link.capture("/foo/str") === None)
        }

        it("should match equal path") {
          assert(link.capture("/foo/bar") === Some(Right(Tuple1(List()))))
        }

        it("should match equal path with param") {
           assert(link.capture("/foo/bar?str=p1") === Some(Right(Tuple1(List("p1")))))
        }

        it("should match equal path with params") {
           assert(link.capture("/foo/bar?str=p1&str=p2") === Some(Right(Tuple1(List("p1", "p2")))))
        }
      }

      describe("with two params") {
        val link = Root / "foo" / "bar" :? param[String]("str") & param[Int]("int")

        it("should not match shorter path") {
          assert(link.capture("/foo") === None)
        }

        it("should not match if static in varfragment doesn't match") {
          assert(link.capture("/foo/str/baz") === None)
        }

        it("should match equal path") {
          assert(link.capture("/foo/bar?str=txt&int=1") === Some(Right(("txt", 1))))
        }
      }

    }
  }

  describe("overlaps") {
    describe("StaticFragment") {
      describe("Root / foo / bar") {
        val link: HPath[HNil] = Root / "foo" / "bar"

        it("overlap /foo/bar") {
          assert(link.overlaps(Root / "foo" / "bar"))
        }

        it("do not overlap /foo/bar/baz") {
          assert(!link.overlaps(Root / "foo" / "baz"))
        }

        it("do not overlap /foo/bar/param[String]") {
          assert(!link.overlaps(Root / "foo" / "bar" / param[String]("")))
        }

        it("overlap /foo/param[String)(bar)") {
          assert(link.overlaps(Root / "foo" / param[String]("bar")))
        }

        it("do not overlap /foo/param[String)(bar)/baz") {
          assert(!link.overlaps(Root / "foo" / param[String]("bar") / "baz"))
        }
      }
    }

    describe("VarFragment") {
      describe("Root / param[String] / bar") {
        val link: HPath[String :: HNil] = Root / param[String]("") / "bar"

        it("overlap /foo/bar") {
          assert(link.overlaps(Root / "foo" / "bar"))
        }

        it("overlap /foo/param[String]") {
          assert(link.overlaps(Root / "foo" / param[String]("")))
        }

        it("do not overlap /foo/param[String]/baz") {
          assert(!link.overlaps(Root / "foo" / param[String]("") / "baz"))
        }
      }
    }
  }
}
