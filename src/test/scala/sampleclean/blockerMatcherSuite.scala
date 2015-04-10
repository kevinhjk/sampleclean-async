package sampleclean

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.Row
import org.scalatest.FunSuite
import sampleclean.clean.deduplication.join.{BlockerMatcherJoinSequence, BlockerMatcherSelfJoinSequence, BroadcastJoin}
import sampleclean.clean.deduplication.matcher.{AllMatcher, Matcher}
import sampleclean.clean.featurize.AnnotatedSimilarityFeaturizer.WeightedJaccardSimilarity
import sampleclean.clean.featurize.Tokenizer.DelimiterTokenizer


class BlockerMatcherSuite extends FunSuite with LocalSCContext {

  val colNames = (0 until 20).toList.map("col" + _.toString)
  val tok = new DelimiterTokenizer(" ")
  val sampleTableName = "test_sample"

  test("matcher") {
    withSampleCleanContext { scc =>
      val row1 = Row("a", "b", "c")
      val row2 = Row("d", "e", "f")
      var m: Matcher = null

      m = new AllMatcher(scc, sampleTableName)

      // does not include equal rows
      val cartesian = Set((row2, row1), (row1, row2))
      assert(m.selfCartesianProduct(Set(row1, row1)) == List())
      assert(m.selfCartesianProduct(Set(row1, row2)).toSet == cartesian)


      val candidates1: RDD[Set[Row]] = scc.getSparkContext().parallelize(Seq(Set(row1, row2)))
      val candidates2: RDD[(Row, Row)] = scc.getSparkContext().parallelize(Seq((row1, row2)))
      // TODO should they be the same?
      //assert(m.matchPairs(candidates1).collect() == m.matchPairs(candidates2).collect())

      // TODO asynchronous matchers
      }
    }



  test("self join sequence") {
    withFullRecords (1,{ scc =>

      val blocker = new WeightedJaccardSimilarity(colNames, scc.getTableContext(sampleTableName), tok, 0.5)
      val bJoin = new BroadcastJoin(scc.getSparkContext(), blocker, false)
      val rdd = scc.getFullTable(sampleTableName)
      val matcher = new AllMatcher(scc, sampleTableName)

      val blockMatch = new BlockerMatcherSelfJoinSequence(scc, sampleTableName, bJoin, List(matcher))
      assert(blockMatch.blockAndMatch(rdd).count() == 100)
    })

}

  test("sample join sequence") {
    withFullRecords(2, { scc =>

      val blocker = new WeightedJaccardSimilarity(colNames, scc.getTableContext(sampleTableName), tok, 0.465)
      val bJoin = new BroadcastJoin(scc.getSparkContext(), blocker, false)
      val rdd1 = scc.getFullTable(sampleTableName)
      val rdd2 = scc.getCleanSample(sampleTableName)
      val matcher = new AllMatcher(scc, sampleTableName)

      val blockMatch = new BlockerMatcherJoinSequence(scc, sampleTableName, bJoin, List(matcher))
      assert(blockMatch.blockAndMatch(rdd2, rdd1).count() >= 40 * 2 + rdd2.count())
      blockMatch.updateContext(scc.getTableContext(sampleTableName).map(x => x + x))
      assert(blocker.context == scc.getTableContext(sampleTableName).map(x => x + x))
    })
  }
}
