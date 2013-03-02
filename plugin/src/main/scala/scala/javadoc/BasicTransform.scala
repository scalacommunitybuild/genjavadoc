package scala.javadoc

trait BasicTransform { this: TransformCake ⇒
  import global._

  def newTransformUnit(unit: CompilationUnit): Unit = {
    superTransformUnit(unit)
    for (c ← flatten(classes)) {
      val out = file(c.file)
      out.println(s"package ${c.pckg};")
      write(out, c)
    }
  }

  var visited: List[Tree] = Nil
  var keep = true
  def noKeep(code: ⇒ Tree): Tree = {
    val old = keep
    keep = false
    try code finally keep = old
  }

  def newTransform(tree: Tree): Tree = {
    def commentText(tp: Position, endPos: Option[Position]) = {
      val ret = if (tp.isDefined) {
        val old = pos
        pos = tp
        if (old.precedes(pos)) {
          (positions.from(old) intersect positions.to(pos)).toSeq map comments filter ScalaDoc lastOption match {
            case Some(c) ⇒ c.text // :+ s"// found in '${between(old, pos)}'"
            case None ⇒
              // s"// empty '${between(old, pos)}' (${pos.lineContent}:${pos.column})" ::
              Nil
          }
        } else Seq("// not preceding") ++ visited.reverse.map(t ⇒ "// " + global.showRaw(t))
      } else Seq("// no position")
      endPos foreach (pos = _)
      visited = Nil
      ret
    }

    def track(t: Tree) = {
      if (!keep && tree.pos.isDefined) {
        visited ::= tree
        pos = tree.pos
      }
      tree
    }

    def endPos(t: Tree) = {
      val traverser = new CollectTreeTraverser({
        case t if t.pos.isDefined ⇒ t.pos
      })
      traverser.traverse(t)
      if (traverser.results.isEmpty) None else Some(traverser.results.max)
    }

    tree match {
      case c: ClassDef if keep ⇒
        withClass(c, commentText(c.pos, None)) {
          superTransform(tree)
        }
      case d: DefDef if keep ⇒
        val (lookat, end) =
          if (d.name == nme.CONSTRUCTOR) {
            if (clazz.get.constructor) (d.symbol.enclClass.pos, None)
            else (d.pos, endPos(d.rhs))
          } else (d.pos, endPos(d.rhs))
        addMethod(d, commentText(lookat, end))
        tree
      case _: ValDef     ⇒ { track(tree) }
      case _: PackageDef ⇒ { track(tree); superTransform(tree) }
      case _: Template   ⇒ { track(tree); superTransform(tree) }
      case _: TypeTree   ⇒ { track(tree) }
      case _             ⇒ { track(tree); noKeep(superTransform(tree)) }
    }
  }

  // list of top-level classes in this unit
  var classes = Vector.empty[ClassInfo]

  // the current class, any level
  var clazz: Option[ClassInfo] = None

  def withClass(c: ImplDef, comment: Seq[String])(block: ⇒ Tree): Tree = {
    val old = clazz
    clazz = Some(ClassInfo(c, comment))
    val ret = block
    clazz = old match {
      case None ⇒
        classes :+= clazz.get; None
      case Some(oc) ⇒ Some(oc.addMember(clazz.get))
    }
    ret
  }

  def addMethod(d: DefDef, comment: Seq[String]) {
    clazz = clazz map (_.addMember(MethodInfo(d, comment)))
  }

}