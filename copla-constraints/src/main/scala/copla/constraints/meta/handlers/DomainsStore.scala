package copla.constraints.meta.handlers

import copla.constraints.meta.CSP
import copla.constraints.meta.constraints.{BindConstraint, EqualityConstraint, InequalityConstraint}
import copla.constraints.meta.domains.Domain
import copla.constraints.meta.events._
import copla.constraints.meta.handlers.DomainsStore.{DomainID, Watches}
import copla.constraints.meta.updates._
import copla.constraints.meta.util.Assertion._
import copla.constraints.meta.variables.{IntVariable, VarWithDomain}

import scala.collection.mutable

class DomainsStore(csp: CSP, base: Option[DomainsStore] = None)
    extends InternalCSPEventHandler
    with slogging.LazyLogging {

  private val domainsById: mutable.ArrayBuffer[Domain] = base match {
    case Some(base) => base.domainsById.clone()
    case _          => mutable.ArrayBuffer()
  }

  /** Tracks which of the domains are ID are free. A free domain ID is materialized by a null value in the
    * domainsById array. This ID might be reused for a new variable. */
  private val emptySpots: mutable.Set[DomainID] = base match {
    case Some(x) => x.emptySpots.clone()
    case _       => mutable.Set()
  }

  /** Associates each variable with a given domain ID which can be used
    * (i) to retrieve its domain from domainsById.
    * (ii) to test for entailed equality (two variables with the same domain ID where enforced to be equal. */
  private val variableIds: mutable.Map[IntVariable, DomainID] = base match {
    case Some(base) => base.variableIds.clone()
    case _          => mutable.Map()
  }

  private val variablesById: mutable.Map[DomainID, mutable.Set[IntVariable]] = base match {
    case Some(x) => mutable.Map(x.variablesById.toSeq.map(p => (p._1, p._2.clone())): _*)
    case _       => mutable.Map()
  }

  private val boundVariables: mutable.Set[IntVariable] = base match {
    case Some(x) => x.boundVariables.clone()
    case _       => mutable.Set()
  }

  private val watches: Watches = base match {
    case Some(x) => x.watches.clone()
    case _ => new Watches()
  }

  /** Returns true if the two variables are subject to an equality constraint. */
  def enforcedEqual(v1: IntVariable, v2: IntVariable): Boolean =
    recorded(v1) && recorded(v2) && id(v1) == id(v2)

  def domOpt(v: IntVariable): Option[Domain] =
    variableIds
      .get(v)
      .map(id => domainsById(id))

  /** Returns the domain of a variable. Throws if the variable has no recorded domain. */
  private def dom(v: IntVariable): Domain = domainsById(variableIds(v))
  private def dom(id: DomainID): Domain   = domainsById(id)

  /** Returns true if the variable has been previously recorded (i.e. is associated with a domain). */
  def recorded(v: IntVariable): Boolean = variableIds.contains(v)

  /** Set the domain of the given variable.
    * Require that the variable was not previously recorded and that domain is not empty.
    * When this is not provable, one should use updateDomain instead. */
  def setDomain(variable: IntVariable, domain: Domain): Unit = {
    require(!recorded(variable))
    require(domain.nonEmpty)
    setDomainImpl(variable, domain)
  }

  private def setId(variable: IntVariable, newId: DomainID): Unit = {
    if (variableIds.contains(variable))
      variablesById(id(variable)) -= variable
    variableIds(variable) = newId
    variablesById.getOrElseUpdate(newId, mutable.Set()) += variable
  }

  private def setDomainImpl(variable: IntVariable, domain: Domain): Unit = {
    val id = variableIds.get(variable) match {
      case Some(x) => x
      case None if emptySpots.nonEmpty =>
        val newId = emptySpots.head
        emptySpots -= newId
        assert3(domainsById(newId) == null)
        setId(variable, newId)
        newId
      case _ =>
        val newId = domainsById.size
        setId(variable, newId)
        newId
    }
    assert2(id <= domainsById.size)

    if (id == domainsById.size)
      domainsById += domain
    else
      domainsById(id) = domain
  }

  def updateDomain(variable: IntVariable, newDomain: Domain): UpdateResult[Seq[DomainChange]] = {
    logger.debug(s"  dom-update: $variable <- $newDomain")
    if (newDomain.isEmpty) {
      inconsistent(s"Empty domain update for variable $variable")
    } else if (!recorded(variable)) {
      setDomainImpl(variable, newDomain)
      consistent(Nil)
    } else if (dom(variable).size > newDomain.size) {
      setDomainImpl(variable, newDomain)
      consistent(varsWithId(id(variable)).map(DomainReduced(_)))
    } else if (dom(variable).size < newDomain.size && !isBound(variable)) {
      // domain increase and there is no bindng constraint forbiding its growth
      setDomainImpl(variable, newDomain)
      consistent(varsWithId(id(variable)).map(DomainExtended(_)))
    } else {
      assert3(isBound(variable) || dom(variable) == newDomain)
      consistent(Nil)
    }
  }

  /** Invoked when a CSP is cloned, the new CSP will append the handler resulting from this method into its own handlers */
  override def clone(newCSP: CSP): InternalCSPEventHandler =
    new DomainsStore(newCSP, Some(this))

  def isBound(variable: IntVariable): Boolean = boundVariables.contains(variable)

  private def id(v: IntVariable): Int = variableIds(v)

  private def varsWithId(id: DomainID): Seq[IntVariable] = {
    assert3(variableIds.toSeq.filter(_._2 == id).map(_._1).toSet == variablesById(id))
    variablesById(id).toSeq
  }


  override def handleEvent(event: Event): Update = event match {
    case NewConstraint(c: BindConstraint) =>
      boundVariables += c.variable
      consistent
    case NewConstraint(x @ Eq(lid, rid)) if lid != rid =>
      //merge
      val commonDomain = domainsById(lid).intersection(domainsById(rid))
      if (commonDomain.isEmpty) {
        inconsistent(s"Equality constraint $x resulted in ${x.v1} and ${x.v2} having an empty domain.")
      } else {
        val changedVariables =
          (if (commonDomain.size < dom(lid).size) varsWithId(lid) else Set()) ++
            (if (commonDomain.size != dom(rid).size) varsWithId(rid) else Set())

        for (v <- varsWithId(rid)) {
          setId(v, lid)
        }
        assert3(varsWithId(rid).isEmpty)
        emptySpots += rid
        domainsById(rid) = null
        domainsById(lid) = commonDomain

        val satisfiedWatches = watches.getEqualityWatches(lid, rid).map(WatchedSatisfied(_))
        val violatedWatches = watches.getDiffWatches(lid, rid).map(WatchedViolated(_))

        for(oid <- watches.equalityWatches.keySet if watches.equalityWatches(oid).contains(rid)) {
          watches.getEqualityWatches(oid, lid) ++= watches.getEqualityWatches(oid, rid)
          watches.removeEqualityWatches(oid, rid)
        }
        for(oid <- watches.diffWatches.keySet if watches.diffWatches(oid).contains(rid)) {
          watches.getDiffWatches(oid, lid) ++= watches.getDiffWatches(oid, rid)
          watches.removeDiffWatches(oid, rid)
        }

        foreach(violatedWatches)(e => csp.addEvent(e)) >>
          foreach(satisfiedWatches)(e => csp.addEvent(e)) >>
          foreach(changedVariables)(v => csp.addEvent(DomainReduced(v)))

      }
    case WatchConstraint(c @ Eq(lid, rid)) if lid == rid =>
      csp.addEvent(WatchedSatisfied(c))
    case WatchConstraint(c @  Eq(lid, rid)) =>
      watches.getEqualityWatches(lid, rid) += c
      consistent

    case WatchConstraint(c @ Diff(lid, rid)) if lid == rid =>
      csp.addEvent(WatchedViolated(c))
    case WatchConstraint(c @ Diff(lid, rid)) =>
      watches.getDiffWatches(lid, rid) += c
      consistent
//      csp.addEvent(WatchedViolated(c))

    case _ =>
      consistent
  }

  /** Deconstructor for Equality constraints. */
  private object Eq {
    def unapply(x: EqualityConstraint): Option[(DomainID, DomainID)] =
      (x.v1, x.v2) match {
        case (left: IntVariable, right: IntVariable) if recorded(left) && recorded(right) =>
          if (id(left) < id(right))
            Some((id(left), id(right)))
          else
            Some((id(right), id(left)))
        case _ =>
          None
      }
  }

  private object Diff {
    def unapply(c: InequalityConstraint): Option[(DomainID, DomainID)] = (c.v1, c.v2) match {
        case (left: IntVariable, right: IntVariable) if recorded(left) && recorded(right) =>
          if (id(left) < id(right))
            Some((id(left), id(right)))
          else
            Some((id(right), id(left)))
        case _ =>
          None
      }
  }

}

object DomainsStore {
  type DomainID = Int

  class Watches(optBase: Option[Watches] = None) {

    val equalityWatches: mutable.Map[DomainID, mutable.Map[DomainID, mutable.Set[EqualityConstraint]]] =
      optBase match {
        case Some(base) =>
          mutable.Map(
            base.equalityWatches.mapValues(m => mutable.Map(m.mapValues(_.clone()).toSeq: _*)).toSeq: _*)
        case _ => mutable.Map()
      }

    val diffWatches: mutable.Map[DomainID, mutable.Map[DomainID, mutable.Set[InequalityConstraint]]] =
      optBase match {
        case Some(base) =>
          mutable.Map(
            base.diffWatches.mapValues(m => mutable.Map(m.mapValues(_.clone()).toSeq: _*)).toSeq: _*)
        case _ => mutable.Map()
      }

    def getEqualityWatches(lid: DomainID, rid: DomainID): mutable.Set[EqualityConstraint] = {
      if (lid <= rid)
        equalityWatches.getOrElseUpdate(lid, mutable.Map()).getOrElseUpdate(rid, mutable.Set())
      else
        getEqualityWatches(rid, lid)
    }

    def removeEqualityWatches(lid: DomainID, rid: DomainID): Unit = {
      if (lid <= rid) {
        if (equalityWatches.contains(lid)) {
          equalityWatches(lid) -= rid
          if (equalityWatches(lid).isEmpty)
            equalityWatches -= lid
        }
      } else {
        removeEqualityWatches(rid, lid)
      }
    }

    def getDiffWatches(lid: DomainID, rid: DomainID): mutable.Set[InequalityConstraint] = {
      if (lid <= rid)
        diffWatches.getOrElseUpdate(lid, mutable.Map()).getOrElseUpdate(rid, mutable.Set())
      else
        getDiffWatches(rid, lid)
    }

    def removeDiffWatches(lid: DomainID, rid: DomainID): Unit = {
      if (lid <= rid) {
        if (diffWatches.contains(lid)) {
          diffWatches(lid) -= rid
          if (diffWatches(lid).isEmpty)
            diffWatches -= lid
        }
      } else {
        removeDiffWatches(rid, lid)
      }
    }

    override def clone: Watches = new Watches(Some(this))
  }
}