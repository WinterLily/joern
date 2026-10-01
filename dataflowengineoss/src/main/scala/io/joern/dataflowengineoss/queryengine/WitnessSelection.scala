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
      val ordered = candidates
        .map(candidate => ((-path(candidate).size, tieBreaker(candidate)), candidate))
        .sortBy(_._1)
        .map(_._2)
      val distinct = ordered.distinctBy(path)
      if (distinct.size > bound) config.diagnostics.foreach(_.record("witness-alternatives"))
      distinct.take(bound)
    }
  }
}
