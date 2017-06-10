package copla.lang.parsing.anml

import copla.lang.model._
import fastparse.core.Parsed.Success

import scala.util.Try

object Parser {

  import fastparse.WhitespaceApi
  val whiteChars = " \r\n\t"

  val White = WhitespaceApi.Wrapper {
    import fastparse.all._
    val white = CharsWhileIn(whiteChars)
    NoTrace(white.rep)
  }
  import fastparse.noApi._
  import White._


  val word: Parser[String] =
    (CharIn(('a' to 'z') ++ ('A' to 'Z')) ~ CharsWhileIn(
      ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9'),
      min = 0)).!
  val typeKW: Parser[Unit] = word.filter(_ == "type").map(x => {}).opaque("type")
  val instanceKW = word.filter(_ == "instance").map(_ => {}).opaque("instance")
  val keywords             = Set("type", "instance", "action", "duration")
  val reservedTypeNames    = Set()
  val nonIdent             = keywords ++ reservedTypeNames

  val ident                    = word.filter(!nonIdent.contains(_)).opaque("identifier")
  val typeName: Parser[String] = word.filter(!keywords.contains(_)).opaque("type-name")
  val variableName             = ident.opaque("variable-name")

  def isFree(id: String, c: Mod) = c.findType(id).orElse(c.findTimepoint(id)).orElse(c.findVariable(id)).isEmpty

  def declaredType(c: Mod): Parser[Type] =
    typeName
      .filter(c.findType(_).isDefined)
      .map(c.findType(_).get)
      .opaque("previously-declared-type")

  def typeDeclaration(c: Mod): Parser[Type] =
    (typeKW ~/
      typeName
        .filter(isFree(_, c))
        .opaque("previously-undefined-type")
        .!
      ~ ("<" ~/ declaredType(c).!).asInstanceOf[Parser[Type]].?
      ~ ";")
      .map {
        case (name, parentOpt) => Type(name, parentOpt)
      }

//  val instancesDeclaration = instance ~ typeName ~ variableName.rep(1) ~ ";"

  def elem(m: Mod): Parser[ModuleElem] =
    typeDeclaration(m)

  def anmlParser(mod: Mod): Parser[Mod] =
    End.map(_ => mod) |
      (Pass ~ elem(mod) ~ Pass).flatMap(elem =>
        mod + elem match {
          case Some(extended) => anmlParser(extended)
          case None           => Fail
      })

  def parse(input: String) = {
    val emptyModule = Mod()
    anmlParser(emptyModule).parse(input)
  }
}
