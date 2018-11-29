package com.hyzs.spark.ml

import com.hyzs.spark.ml.ConvertLibsvm.saveRdd
import com.hyzs.spark.mllib.evaluation.ConfusionMatrix
import com.hyzs.spark.utils.SparkUtils._
import org.apache.spark.ml.classification.GBTClassifier
import org.apache.spark.ml.evaluation.{MulticlassClassificationEvaluator, RegressionEvaluator}
import org.apache.spark.ml.regression.{GBTRegressionModel, GBTRegressor}
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.{GradientBoostedTrees, RandomForest}
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.tree.configuration.BoostingStrategy
import org.apache.spark.mllib.tree.impurity.{Entropy, Gini, Variance}
import org.apache.spark.mllib.util.MLUtils
import ml.dmlc.xgboost4j.scala.spark.XGBoostClassifier
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics
import org.apache.spark.mllib.tree.model.GradientBoostedTreesModel
/**
  * Created by xk on 2018/10/26.
  */
object ModelPrediction {

  import spark.implicits._
  val libsvmPath = "/user/hyzs/libsvm/particles_train.libsvm"
  def prepareData(): (RDD[LabeledPoint],
    RDD[LabeledPoint],
    RDD[LabeledPoint]) = {
    val data: Seq[RDD[LabeledPoint]] = MLUtils.loadLibSVMFile(sc, libsvmPath).randomSplit(Array(0.3, 0.3, 0.4))
    (data(0), data(1), data(2))
  }

/*  def printEvaluation[T <: DecisionTreeModel](testData:RDD[LabeledPoint], model: T): Unit = {
    val predAndLabels = testData.map { point =>
      val prediction = model.predict(point.features)
      (prediction, point.label)
    }.collect()
    val confusion = new ConfusionMatrix(predAndLabels)

    println("model precision: " + confusion.precision)
    println("model recall: " + confusion.recall)
    println("model accuracy: " + confusion.accuracy)
  }*/

  def randomForest(trainingData: RDD[LabeledPoint],
                   validData: RDD[LabeledPoint],
                   testData:RDD[LabeledPoint]): Unit = {
    // Train a RandomForest model.
    // Empty categoricalFeaturesInfo indicates all features are continuous.

    val numClasses = 2
    val categoricalFeaturesInfo = Map[Int, Int]()
    val numTrees = 10 // Use more in practice.
    val featureSubsetStrategy = "auto" // Let the algorithm choose.
    val impurity = "gini"
    val maxDepth = 4
    val maxBins = 32
    val startTime = System.currentTimeMillis()
    val model = RandomForest.trainClassifier(trainingData, numClasses, categoricalFeaturesInfo,
      numTrees, featureSubsetStrategy, impurity, maxDepth, maxBins)
    val costTime = System.currentTimeMillis() - startTime
    val predAndLabels = testData.map { point =>
      val prediction = model.predict(point.features)
      (prediction, point.label)
    }.collect()
    val confusion = new ConfusionMatrix(predAndLabels)

    println("cost time milliseconds: " + costTime)
    println("model precision: " + confusion.precision)
    println("model recall: " + confusion.recall)
    println("model accuracy: " + confusion.accuracy)
    println("model f1: " + confusion.f1_score)
  }

  def GBT(trainingData: RDD[LabeledPoint],
          validData: RDD[LabeledPoint],
          testData:RDD[LabeledPoint],
          goal:String): Unit = {


    // Train a GradientBoostedTrees model.
    // The defaultParams for Classification use LogLoss by default.
    // goal should be "Classification" or "Regression"
    val boostingStrategy: BoostingStrategy = BoostingStrategy.defaultParams(goal)
    boostingStrategy.setNumIterations(100) // Note: Use more iterations in practice. eg. 10, 20
    boostingStrategy.setLearningRate(0.005)
    //boostingStrategy.treeStrategy.setNumClasses(2)
    boostingStrategy.treeStrategy.setMaxDepth(5)
    boostingStrategy.treeStrategy.setImpurity(Variance)
    boostingStrategy.treeStrategy.setMaxBins(32)
    // Empty categoricalFeaturesInfo indicates all features are continuous.
    // boostingStrategy.treeStrategy.categoricalFeaturesInfo = Map[Int, Int]()
    //boostingStrategy.treeStrategy.setImpurity(Entropy)
    //boostingStrategy.treeStrategy.setImpurity(Gini)

    // without validation
    val model = GradientBoostedTrees.train(trainingData, boostingStrategy)
    //val model = new GradientBoostedTrees(boostingStrategy).runWithValidation(trainingData, validData)

    if(goal == "Classification"){
      val predAndLabels = testData.map { point =>
        val prediction = model.predict(point.features)
        (prediction, point.label)
      }.collect()
      val confusion = new ConfusionMatrix(predAndLabels)
      println("model precision: " + confusion.precision)
      println("model recall: " + confusion.recall)
      println("model accuracy: " + confusion.accuracy)
      println("model f1: " + confusion.f1_score)
    } else if (goal == "Regression"){
      val labelsAndPredictions = testData.map { point =>
        val prediction = model.predict(point.features)
        (point.label, prediction)
      }
      val testMSE = labelsAndPredictions.map{ case (v, p) => math.pow(v - p, 2) }.mean()
      val rmse = math.sqrt(testMSE)
      println(s"Test Mean Squared Error = $testMSE")
      println(s"Root Mean Squared Error = $rmse")
      println(s"Learned regression tree model:\n ${model.toDebugString}")
      val modelPath = "/user/hyzs/model/gbt_regression"
      println(s"save model to $modelPath")
      if(checkHDFileExist(modelPath)) dropHDFiles(modelPath)
      model.save(sc, modelPath)
    } else throw new IllegalArgumentException(s"$goal is not supported by boosting.")

  }


  def GBT_classifier(): Unit = {
    val data = spark.read.format("libsvm").load(libsvmPath)
    val Array(trainingData, testData) = data.randomSplit(Array(0.6, 0.4))

    // Train a GBT model.
    val gbt = new GBTClassifier()
      .setLabelCol("label")
      .setFeaturesCol("features")
      .setMaxIter(10)
      .setFeatureSubsetStrategy("auto")

    val model = gbt.fit(trainingData)

    // Make predictions.
    val predictions = model.transform(testData)

    // Select example rows to display.
    // predictions.select("prediction", "label", "features").show(5)

    // Select (prediction, true label) and compute test error.
    val evaluator = new MulticlassClassificationEvaluator()
      .setLabelCol("label")
      .setPredictionCol("prediction")
      .setMetricName("accuracy")
    val accuracy = evaluator.evaluate(predictions)

    val predAndLabels = predictions.select("prediction", "label")
      .map(row => (row.getDouble(0), row.getDouble(1)))
      .rdd
    val metrics = new BinaryClassificationMetrics(predAndLabels)
    metrics.precisionByThreshold().collect()
    metrics.recallByThreshold().collect()
    metrics.areaUnderROC()

    println(s"Learned classification GBT model:\n ${model.toDebugString}")

  }

  def xgboost_ml(): Unit = {
    val data = spark.read.format("libsvm").load(libsvmPath)
    val Array(trainingData, testData) = data.randomSplit(Array(0.6, 0.4))
    val xgbParam = Map("eta" -> 0.1f,
      "max_depth" -> 6,
      "objective" -> "binary:logistic",
      "num_round" -> 10)

    val xgbClassifier = new XGBoostClassifier(xgbParam).
      setFeaturesCol("features").
      setLabelCol("label")

    val xgbClassificationModel = xgbClassifier.fit(trainingData)
    val predictions = xgbClassificationModel.transform(testData)

    val predAndLabels = predictions.select("prediction", "label")
      .map(row => (row.getDouble(0), row.getDouble(1)))
      .rdd
      .collect()
    val confusion = new ConfusionMatrix(predAndLabels)
    println("model precision: " + confusion.precision)
    println("model recall: " + confusion.recall)
    println("model accuracy: " + confusion.accuracy)
    println("model f1: " + confusion.f1_score)

  }

  def predictModel(): Unit = {
    val modelPath = "/user/hyzs/model/gbt_regression"
    val dataPath = "/user/hyzs/convert/test_result/test_result.libsvm"
    val model = GradientBoostedTreesModel.load(sc, modelPath)
    val testData = MLUtils.loadLibSVMFile(sc, dataPath)
    val preds = testData.map{ record =>
      model.predict(record.features).toString
    }
    saveRdd(preds, "/user/hyzs/convert/test_result/preds.txt")

  }

  def main(args: Array[String]): Unit = {
    /* val (trainingData, validData, testData) = prepareData()
     println("random forest =======")
     val randomForestModel = randomForest(trainingData, validData, testData)
     println("gbt =======")
     val gbt = GBT(trainingData, validData, testData)*/
/*    println("xgboost =======")
    xgboost_ml()*/
    val rawData = MLUtils
      .loadLibSVMFile(sc, "/user/hyzs/convert/train_result/train_result.libsvm")
      .randomSplit(Array(0.7, 0.3))
    GBT(rawData(0), null, rawData(1), "Regression")
    predictModel()

  }
}