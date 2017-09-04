package copla.constraints.meta.constraints

import copla.constraints.meta.constraints.ConstraintSatisfaction.{SATISFIED, UNDEFINED, VIOLATED}
import copla.constraints.meta.{CSP, CSPView}
import copla.constraints.meta.events.Event
import copla.constraints.meta.variables.IVar

class ConjunctionConstraint(val constraints: Seq[Constraint]) extends Constraint {

  override def onPost(implicit csp: CSPView) = {
    super.onPost ++ constraints.map(c => Post(c))
  }

  override def variables(implicit csp: CSPView): Set[IVar] = Set()

  override def subconstraints(implicit csp: CSPView) = constraints

  override def satisfaction(implicit csp: CSPView): Satisfaction =
    if (constraints.forall(_.isSatisfied))
      ConstraintSatisfaction.SATISFIED
    else if (constraints.exists(_.isViolated))
      ConstraintSatisfaction.VIOLATED
    else
      ConstraintSatisfaction.UNDEFINED

  override def propagate(event: Event)(implicit csp: CSPView) = satisfaction match {
    case UNDEFINED => Undefined()
    case SATISFIED => Satisfied()
    case VIOLATED  => Inconsistency
  }

  override def toString = "(" + constraints.mkString(" && ") + ")"

  /** Returns the invert of this constraint (e.g. === for an =!= constraint) */
  override def reverse: Constraint =
    new DisjunctiveConstraint(constraints.map(_.reverse))
}