package copla.planning.causality.support

import copla.constraints.meta.{CSP, CSPView}
import copla.constraints.meta.constraints.ConjunctionConstraint
import copla.constraints.meta.decisions.{Decision, DecisionConstraint, DecisionOption}
import copla.constraints.meta.util.Assertion._
import copla.lang.model.core
import copla.planning.causality.{CausalHandler, DecisionPending, SupportByActionInsertion, SupportByExistingChange}
import copla.planning.events.{ActionInsertion, PlanningHandler}

class SupportDecision(val supportVar: SupportVar) extends Decision {

  def context(implicit csp: CSPView) : CausalHandler = csp.getHandler(classOf[PlanningHandler]).getHandler(classOf[CausalHandler])

  /** Returns true is this decision is still pending. */
  override def pending(implicit csp: CSPView): Boolean = {
    assert3(supportVar.dom.contains(DecisionPending) == supportVar.domain.contains(0))
    supportVar.domain.contains(0)
  }

  /** Estimate of the number of options available (typically used for variable ordering). */
  override def numOptions(implicit csp: CSPView): Int = {
    if(pending)
      supportVar.domain.size -1
    else
      supportVar.domain.size
  }

  /** Options to advance this decision.
    * Note that the decision can still be pending after applying one of the options.
    * A typical set of options for binary search is [var === val, var =!= val]. */
  override def options(implicit csp: CSPView): Seq[DecisionOption] = {
    val tmp = supportVar.dom.valuesWithIntRepresentation
    val opts = supportVar.dom.valuesWithIntRepresentation
      .filter(tup => tup._1 != DecisionPending)
      .map {
        case (s: SupportByActionInsertion, _) =>
          new PendingSupportOption(s.a.act, supportVar)
        case (s: SupportByExistingChange, i) =>
          DecisionConstraint(supportVar === i)
        case (DecisionPending, _) =>
          shapeless.unexpected
      }
    assert3(opts.size == numOptions)
    opts.toSeq
  }

  override def toString : String = s"support-decision@[${supportVar.target.ref}]"
}

class PendingSupportOption(action: core.ActionTemplate, supportFor: SupportVar) extends DecisionOption {
  /** This method should enforce the decision option in the given CSP. */
  override def enforceIn(csp: CSP) {
    csp.addEvent(ActionInsertion(action, Some(supportFor)))

    // forbid any action insertion since we already made one
    supportFor.dom(csp).valuesWithIntRepresentation.foreach{
      case (s: SupportByActionInsertion, i) =>
        csp.post(supportFor =!= i)
      case _ =>
    }
  }

  override def negate(implicit csp: CSPView): DecisionOption = {
    // forbid this action insertion
    val constraints = supportFor.dom(csp).valuesWithIntRepresentation.toList.collect{
      case (s: SupportByActionInsertion, i) if s.a.act == action =>
        supportFor =!= i
    }
    DecisionConstraint(new ConjunctionConstraint(constraints))
  }

  override def toString : String = s"pending-support: of '$supportFor by $action"
}
