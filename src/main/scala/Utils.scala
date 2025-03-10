import java.io.File
import scala.collection.parallel.CollectionConverters.*
import scala.collection.parallel.ParSeq
import scala.sys.process.*
import org.apache.spark.sql.{DataFrame, SparkSession, Row}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import scala.sys.process._
import scala.util.Try

case class Utils():
  var working_directory: String = sys.props("user.dir")
  var impute_directory: String = "Impute"
  var plots_directory: String = "Plots"
  var allele_directory: String = "Allele_frequencies"
  var chromosomeNames: ParSeq[Any] = ParSeq()

  def isMale: Boolean = {
    true
  }


  private def checkCorrectExecution(path_to_directory: String): Unit = {
    val dir = new File(path_to_directory)
    if (!dir.exists()) {
      val created = dir.mkdir()
      require(created, s"Patient directory couldn't be created")
    }
  }

  def createPatientDirectory(patient_name: String): Unit = {

    working_directory = s"$working_directory/$patient_name"
    plots_directory = s"$working_directory/$plots_directory"
    impute_directory = s"$working_directory/$impute_directory"
    allele_directory = s"$working_directory/$allele_directory"

    val new_directories: Seq[String] = Seq(working_directory, plots_directory, impute_directory, allele_directory)

    new_directories.foreach(checkCorrectExecution)

  }


  /**
   * Analyzes samtools idxstats output to determine if Chromosome X's Length_per_Read
   * is within 0.1 of the average of other chromosomes.
   *
   * @param sampleName Path to the BAM file to analyze
   * @return Boolean indicating if the comparison is within the threshold
   */
  def analyzeIdxstats(sampleName: String): Boolean = {
    // Create a Spark session
    val spark = SparkSession.builder()
      .appName("SamtoolsIdxstatsAnalyzer")
      .master("local[*]")
      .getOrCreate()

    try {
      // Run samtools command and capture output
      val command = s"samtools idxstats $sampleName | sort"
      val output = Try(command.!!).getOrElse {
        spark.stop()
        throw new RuntimeException(s"Failed to execute command: $command")
      }

      // Define schema for the DataFrame
      val schema = StructType(Array(
        StructField("Chromosome", StringType, nullable = false),
        StructField("Length", LongType, nullable = false),
        StructField("Mapped", LongType, nullable = false),
        StructField("Unmapped", LongType, nullable = false)
      ))

      // Convert output to Row objects
      val rows = output.trim.split("\n").map { line =>
        val fields = line.split("\t")
        if (fields.length == 4) {
          Row(fields(0), fields(1).toLong, fields(2).toLong, fields(3).toLong)
        } else {
          Row("invalid", 0L, 0L, 0L) // Handle invalid lines
        }
      }

      // Create DataFrame from rows and schema
      val df = spark.createDataFrame(
        spark.sparkContext.parallelize(rows.toSeq),
        schema
      )

      // Filter chromosomes and add Length_per_Read column
      val filteredDf = df.filter(!col("Chromosome").contains("_") && col("Chromosome") =!= "chrM")
        .withColumn("Length_per_Read", col("Mapped") / col("Length"))

      // Extract chrX Length_per_Read
      val chrXStatsRows = filteredDf.filter(col("Chromosome") === "chrX")
        .select("Length_per_Read")
        .collect()

      if (chrXStatsRows.isEmpty) {
        spark.stop()
        throw new RuntimeException("Chromosome X not found in the data")
      }

      val chrXLengthPerRead = chrXStatsRows(0).getDouble(0)

      // Calculate average Length_per_Read for other chromosomes
      val statsRow = filteredDf.agg(
        sum("Length_per_Read").as("totalLengthPerRead"),
        count("*").as("count")
      ).collect()(0)

      val totalLengthPerRead = statsRow.getDouble(0)
      val rowCount = statsRow.getLong(1)

      val avgLengthPerRead = (totalLengthPerRead - chrXLengthPerRead) / (rowCount - 1)

      // Compare values
      val comparison = Math.abs(chrXLengthPerRead - avgLengthPerRead)
      val result = comparison < 0.1

      spark.stop()
      result

    } catch {
      case e: Exception =>
        spark.stop()
        throw e
    }
  }

  // Helper function to create a list of chromosomes (1-22 + X)
  def createChromosomeList(): Seq[String] = {
    val autosomes = (1 to 22).map(i => s"chr$i")
    autosomes :+ "chrX"
  }

  def setChromosomesNames(bamFile: String): Unit = {
    val command = Seq(
      "samtools",
      "view", "-H", bamFile
    ).mkString(" ")
    println(command)


    val chromosomePrefix = command.!!

    if (chromosomePrefix.contains("chr")) {
      ((1 to 22).map(n => s"chr$n") :+ "chrX").toList.par

    } else {
      chromosomeNames = ((1 to 22) :+ "chrX").toList.par
    }
  }






