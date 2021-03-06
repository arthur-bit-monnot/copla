package copla.constraints.meta.constraints

import copla.constraints.meta.{CSP, CSPView}
import copla.constraints.meta.constraints.ConstraintSatisfaction._
import copla.constraints.meta.decisions.VarBindingDecision
import copla.constraints.meta.domains.Domain
import copla.constraints.meta.events._
import copla.constraints.meta.util.Assertion._
import copla.constraints.meta.variables._

class DisjunctiveConstraint(val disjuncts: Seq[Constraint]) extends Constraint {

  val decisionVar = new IntVar(Domain(disjuncts.indices.toSet)) {
    override def isDecisionVar: Boolean = false
    override def toString: String = s"disjunctive-dec-var[${DisjunctiveConstraint.this}]"
  }

  val decision = VarBindingDecision(decisionVar)

  override def variables(implicit csp: CSPView): Set[IVar] = Set(decisionVar)

  override def subconstraints(implicit csp: CSPView) = disjuncts

  override def onPost(implicit csp: CSPView): Seq[OnPostChange] = super.onPost :+ AddDecision(decision)

  override def satisfaction(implicit csp: CSPView): Satisfaction = {
    val satisfactions = disjuncts.map(_.satisfaction)
    if (satisfactions.contains(SATISFIED))
      SATISFIED
    else if(satisfactions.contains(EVENTUALLY_SATISFIED))
      EVENTUALLY_SATISFIED
    else if (satisfactions.contains(UNDEFINED))
      UNDEFINED
    else
      VIOLATED
  }

  override def propagate(event: Event)(implicit csp: CSPView) = {
    val dom = decisionVar.domain
    if (dom.isSingleton && disjuncts(dom.head).isSatisfied) {
      Satisfied()
    } else {
      event match {
        case WatchedSatisfied(c) =>
          assert3(c.eventuallySatisfied)
          assert3(eventuallySatisfied)
          Satisfied(RetractDecision(decision))

        case WatchedViolated(c) =>
          assert3(c.eventuallyViolated)
          val id = disjuncts.indexOf(c)
          if (decisionVar.boundTo(id)) {
            assert3(eventuallyViolated)
            Inconsistency
          } else if (dom.contains(id)) {
            Undefined(UpdateDomain(decisionVar, dom - id))
          } else {
            Undefined()
          }
        case DomainReduced(v) =>
          assert3(v == decisionVar)
          if (dom.isEmpty) {
            assert3(isViolated)
            Inconsistency
          } else if (dom.isSingleton) {
            Undefined(Post(disjuncts(dom.head)))
          } else {
            Undefined()
          }
        case NewConstraint(_) =>
          val satisfactions = decisionVar.domain.values.map(i => (i, disjuncts(i).satisfaction))
          val valids =  satisfactions.collectFirst { case (i, s) if s.isInstanceOf[EventuallySatisfied] => i }
          val invalids = satisfactions.collect { case (i, s) if s.isInstanceOf[EventuallyViolated] => i }.toSet

          valids match {
            case Some(_) =>
              assert3(eventuallySatisfied)
              Satisfied(RetractDecision(decision))
            case None if invalids.isEmpty =>
              Undefined()
            case _ =>
              val newDomain = decisionVar.domain -- invalids
              Undefined(UpdateDomain(decisionVar, newDomain))
          }
      }
    }
  }

  override def toString = "(" + disjuncts.mkString(" || ") + ")"

  override def ||(c: Constraint) =
    new DisjunctiveConstraint(c :: disjuncts.toList)

  /** Returns the invert of this constraint (e.g. === for an =!= constraint) */
  override def reverse: Constraint =
    new ConjunctionConstraint(disjuncts.map(_.reverse))
}
