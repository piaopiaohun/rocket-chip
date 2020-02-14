// See LICENSE.SiFive for license details.

package freechips.rocketchip.stage.phases


import chipsalliance.rocketchip.config.Parameters
import chisel3.stage.phases.Elaborate
import firrtl.AnnotationSeq
import firrtl.annotations.NoTargetAnnotation
import firrtl.options.{Phase, PreservesAll, StageOptions}
import firrtl.options.Viewer.view
import freechips.rocketchip.stage.RocketChipOptions
import freechips.rocketchip.subsystem.RocketTilesKey
import freechips.rocketchip.system.{RegressionTestSuite, RocketTestSuite, TestGeneration}
import freechips.rocketchip.tile.XLen
import freechips.rocketchip.util.HasRocketChipStageUtils

import scala.collection.mutable

case class RocketTestSuiteAnnotation(tests: Seq[RocketTestSuite]) extends NoTargetAnnotation

class AddDefaultTests extends Phase with PreservesAll[Phase] with HasRocketChipStageUtils {

  override val prerequisites = Seq(classOf[Checks], classOf[Elaborate])
  override val dependents = Seq(classOf[GenerateTestSuiteMakefrags])

  override def transform(annotations: AnnotationSeq): AnnotationSeq = {
    import freechips.rocketchip.system.DefaultTestSuites._
    val params: Parameters = getConfig(view[RocketChipOptions](annotations).configNames.get).toInstance
    val xlen = params(XLen)

    val tests = scala.collection.mutable.Buffer[RocketTestSuite]() // FIXME not growable

    val regressionTests = mutable.LinkedHashSet(
      "rv64ud-v-fcvt",
      "rv64ud-p-fdiv",
      "rv64ud-v-fadd",
      "rv64uf-v-fadd",
      "rv64um-v-mul",
      "rv64mi-p-breakpoint",
      "rv64uc-v-rvc",
      "rv64ud-v-structural",
      "rv64si-p-wfi",
      "rv64um-v-divw",
      "rv64ua-v-lrsc",
      "rv64ui-v-fence_i",
      "rv64ud-v-fcvt_w",
      "rv64uf-v-fmin",
      "rv64ui-v-sb",
      "rv64ua-v-amomax_d",
      "rv64ud-v-move",
      "rv64ud-v-fclass",
      "rv64ua-v-amoand_d",
      "rv64ua-v-amoxor_d",
      "rv64si-p-sbreak",
      "rv64ud-v-fmadd",
      "rv64uf-v-ldst",
      "rv64um-v-mulh",
      "rv64si-p-dirty",
      "rv32mi-p-ma_addr",
      "rv32mi-p-csr",
      "rv32ui-p-sh",
      "rv32ui-p-lh",
      "rv32uc-p-rvc",
      "rv32mi-p-sbreak",
      "rv32ui-p-sll")

    // TODO: for now only generate tests for the first core in the first subsystem
    params(RocketTilesKey).headOption.map { tileParams =>
      val coreParams = tileParams.core
      val vm = coreParams.useVM
      val env = if (vm) List("p", "v") else List("p")
      coreParams.fpu foreach { case cfg =>
        if (xlen == 32) {
          tests ++= env.map(rv32uf)
          if (cfg.fLen >= 64)
            tests ++= env.map(rv32ud)
        } else {
          tests += rv32udBenchmarks
          tests ++= env.map(rv64uf)
          if (cfg.fLen >= 64)
            tests ++= env.map(rv64ud)
        }
      }
      if (coreParams.useAtomics) {
        if (tileParams.dcache.flatMap(_.scratch).isEmpty)
          tests ++= env.map(if (xlen == 64) rv64ua else rv32ua)
        else
          tests ++= env.map(if (xlen == 64) rv64uaSansLRSC else rv32uaSansLRSC)
      }
      if (coreParams.useCompressed) tests ++= env.map(if (xlen == 64) rv64uc else rv32uc)
      val (rvi, rvu) =
        if (xlen == 64) ((if (vm) rv64i else rv64pi), rv64u)
        else ((if (vm) rv32i else rv32pi), rv32u)

      tests ++= rvi.map(_ ("p"))
      tests ++= (if (vm) List("v") else List()).flatMap(env => rvu.map(_ (env)))
      tests += benchmarks

      /* Filter the regression tests based on what the Rocket Chip configuration supports */
      val extensions = {
        val fd = coreParams.fpu.map {
          case cfg if cfg.fLen >= 64 => "fd"
          case _ => "f"
        }
        val m = coreParams.mulDiv.map { case _ => "m" }
        fd ++ m ++ Seq(if (coreParams.useRVE) Some("e") else Some("i"),
          if (coreParams.useAtomics) Some("a") else None,
          if (coreParams.useCompressed) Some("c") else None)
          .flatten
          .mkString("")
      }
      val re = s"""^rv$xlen[usm][$extensions].+""".r
      regressionTests.retain {
        case re() => true
        case _ => false
      }
      tests += new RegressionTestSuite(regressionTests)
    }
    RocketTestSuiteAnnotation(tests) +: annotations
  }

}
