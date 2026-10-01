package io.joern.dataflowengineoss.queryengine

private[queryengine] object WitnessSelection {
  def select[A](
    candidates: List[A],
    path: A => Vector[PathElement],
    config: EngineConfig,
    tieBreaker: A => String
  ): List[A] = {
    if (candidates.size == 1) return candidates
    val bound =
      if (config.maxWitnessesPerEndpoint > 1 && io.joern.dataflowengineoss.isDart(path(candidates.head).head.node))
        config.maxWitnessesPerEndpoint
      else 1
    if (bound == 1) {
      val longest  = candidates.map(candidate => path(candidate).size).max
      val selected = candidates.filter(candidate => path(candidate).size == longest)
      if (selected.size == 1) selected else List(selected.minBy(tieBreaker))
    } else {
      if (candidates.tail.forall(_ == candidates.head)) return candidates.take(1)
      val groups   = candidates.groupBy(candidate => path(candidate).size).toList.sortBy(-_._1).iterator
      val selected = List.newBuilder[A]
      var size     = 0
      var omitted  = false
      while (groups.hasNext && size < bound) {
        val group    = groups.next()._2
        val distinct = group match {
          case List(only) => List(only)
          case _ => group.map(candidate => (tieBreaker(candidate), candidate)).sortBy(_._1).map(_._2).distinctBy(path)
        }
        val available = bound - size
        selected ++= distinct.take(available)
        size += math.min(available, distinct.size)
        omitted ||= distinct.size > available
      }
      // Different lengths cannot be duplicate paths, so unvisited groups imply pruning.
      if (omitted || groups.hasNext) config.diagnostics.foreach(_.record("witness-alternatives"))
      selected.result()
    }
  }
}
