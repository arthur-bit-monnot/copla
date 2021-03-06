package copla.constraints.meta.constraints

import copla.constraints.meta.{CSP, CSPView}
import copla.constraints.meta.events.Event
import copla.constraints.meta.util.Assertion._
import copla.constraints.meta.variables.{IVar, IntVariable, VariableSeq}

trait EqualityConstraint extends Constraint {

  def v1: IVar
  def v2: IVar

  override def toString = s"$v1 === $v2"
}

class VariableEqualityConstraint(override val v1: IntVariable, override val v2: IntVariable)
    extends EqualityConstraint {

  override def variables(implicit csp: CSPView): Set[IVar] = Set(v1, v2)

  override def propagate(event: Event)(implicit view: CSPView) = {
    val d1 = view.dom(v1)
    val d2 = view.dom(v2)

    if (d1.emptyIntersection(d2)) {
      Inconsistency
    } else if (d1.isSingleton) {
      Satisfied(UpdateDomain(v2, d1))
    } else if (d2.isSingleton) {
      Satisfied(UpdateDomain(v1, d2))
    } else {
      val inter = d1 intersection d2
      if (inter.isSingleton) {
        Satisfied(UpdateDomain(v1, inter), UpdateDomain(v2, inter))
      } else {
        Undefined(UpdateDomain(v1, inter), UpdateDomain(v2, inter))
      }
    }
  }

  override def satisfaction(implicit csp: CSPView): Satisfaction = {
    val d1 = csp.dom(v1)
    val d2 = csp.dom(v2)

    if (d1.isSingleton && d2.isSingleton && d1.values.head == d2.values.head)
      ConstraintSatisfaction.SATISFIED
    else if(csp.domains.enforcedEqual(v1, v2))
      ConstraintSatisfaction.EVENTUALLY_SATISFIED
    else if (d1.emptyIntersection(d2))
      ConstraintSatisfaction.VIOLATED
    else
      ConstraintSatisfaction.UNDEFINED
  }

  override def reverse: VariableInequalityConstraint = new VariableInequalityConstraint(v1, v2)
}

class VariableSeqEqualityConstraint(override val v1: VariableSeq, override val v2: VariableSeq)
    extends ConjunctionConstraint(v1.variables.zip(v2.variables).map(p => p._1 === p._2))
    with EqualityConstraint {
  require(v1.variables.size == v2.variables.size)

  override def reverse: VariableSeqInequalityConstraint =
    new VariableSeqInequalityConstraint(v1, v2)
}
