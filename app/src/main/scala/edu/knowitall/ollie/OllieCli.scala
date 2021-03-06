package edu.knowitall.ollie;

import java.io.File
import java.io.PrintWriter
import java.io.OutputStreamWriter
import java.nio.charset.MalformedInputException
import scala.io.Source
import edu.knowitall.common.Resource.using
import edu.knowitall.ollie.confidence.OllieConfidenceFunction
import edu.knowitall.openparse.OpenParse
import edu.knowitall.tool.parse.MaltParser
import scopt.OptionParser
import edu.knowitall.tool.segment.Segmenter
import edu.knowitall.common.Timing
import edu.knowitall.tool.sentence.OpenNlpSentencer
import java.text.DecimalFormat
import java.net.URL
import edu.knowitall.tool.parse.graph.DependencyGraph
import edu.knowitall.ollie.confidence.OllieFeatureSet

/**
  * An entry point to use Ollie on the command line.
  */
object OllieCli {
  /** A definition of command line arguments. */
  abstract class Settings {
    def inputFiles: Option[Seq[File]]
    def outputFile: Option[File]

    def encoding: String

    def modelUrl: URL
    def confidenceModelUrl: Option[URL]
    def confidenceThreshold: Double
    def openparseConfidenceThreshold: Double

    def maltModelFile: Option[File]

    def parseInput: Boolean
    def splitInput: Boolean
    def outputFormat: OutputFormat
    def parallel: Boolean
    def invincible: Boolean
  }

  sealed abstract class OutputFormat {
    def header: Option[String]
    def format(conf: Double, extr: OllieExtractionInstance): String
  }
  object OutputFormat {
    val confFormatter = new DecimalFormat("#.###")
    def parse(format: String): OutputFormat = {
      format.toLowerCase match {
        case "interactive" => InteractiveFormat
        case "tabbed" => TabbedFormat
        case "tabbedsingle" => TabbedSingleColumnFormat
        case "serialized" => SerializedFormat
      }
    }
  }
  case object InteractiveFormat extends OutputFormat {
    def header = None
    def format(conf: Double, inst: OllieExtractionInstance): String =
      OutputFormat.confFormatter.format(conf) + ": " + inst.extr
  }
  case object TabbedFormat extends OutputFormat {
    def headers = Seq("confidence", "arg1", "rel", "arg2", "enabler", "attribution", "text", "pattern", "dependencies")
    def header = Some(headers.mkString("\t"))
    def format(conf: Double, inst: OllieExtractionInstance): String =
      Iterable(OutputFormat.confFormatter.format(conf),
        inst.extr.arg1.text,
        inst.extr.rel.text,
        inst.extr.arg2.text,
        inst.extr.enabler.map(_.text),
        inst.extr.attribution.map(_.text),
        inst.sent.text,
        inst.pat,
        inst.sent.serialize).mkString("\t")
  }
  case object TabbedSingleColumnFormat extends OutputFormat {
    def headers = Seq("confidence", "extraction", "enabler", "attribution", "text", "pattern", "dependencies")
    def header = Some(headers.mkString("\t"))
    def format(conf: Double, inst: OllieExtractionInstance): String =
      Iterable(OutputFormat.confFormatter.format(conf),
        inst.extr.toString,
        inst.extr.enabler.map(_.text),
        inst.extr.attribution.map(_.text),
        inst.sent.text,
        inst.pat,
        inst.sent.serialize).mkString("\t")
  }
  case object SerializedFormat extends OutputFormat {
    def header = None
    def format(conf: Double, inst: OllieExtractionInstance): String =
      OutputFormat.confFormatter.format(conf) + "\t" + inst.extr.toString + "\t" + inst.tabSerialize
  }

  /** Size to group for parallelism. */
  private val CHUNK_SIZE = 10000

  def main(args: Array[String]): Unit = {
    object settings extends Settings {
      var inputFiles: Option[Seq[File]] = None
      var outputFile: Option[File] = None

      var encoding: String = "UTF-8"

      var modelUrl: URL = OpenParse.defaultModelUrl
      var confidenceModelUrl: Option[URL] = Some(OllieConfidenceFunction.defaultModelUrl)
      var confidenceThreshold: Double = 0.0
      var openparseConfidenceThreshold: Double = 0.005

      var maltModelFile: Option[File] = None

      var parseInput: Boolean = true
      var splitInput: Boolean = false
      var outputFormat: OutputFormat = InteractiveFormat
      var parallel: Boolean = false
      var invincible: Boolean = false

      var showUsage: Boolean = false
    }

    // define the argument parser
    val argumentParser = new OptionParser("ollie") {
      arglistOpt("<file>", "input text file (one sentence per line unless --split is specified)", { path: String =>
        val file = new File(path)
        require(file.exists, "file does not exist: " + file)
        settings.inputFiles = Some(settings.inputFiles.getOrElse(Vector.empty) :+ file)
      })

      opt(Some("o"), "output", "<file>", "output file (otherwise stdout)", { path: String =>
        settings.outputFile = Some(new File(path))
      })

      opt(Some("e"), "encoding", "<encoding>", "character encoding (UTF8 by default)", { encoding: String =>
        settings.encoding = encoding
      })

      opt(Some("m"), "model", "<file>", "model file", { path: String =>
        val file = new File(path)
        require(file.exists, "file does not exist: " + path)
        settings.modelUrl = file.toURI.toURL
      })

      opt(Some("c"), "confidence model", "<file>", "model file", { path: String =>
        if (path equalsIgnoreCase "None") {
          settings.confidenceModelUrl = None
        } else {
          val file = new File(path)
          require(file.exists, "file does not exist: " + path)
          settings.confidenceModelUrl = Some(file.toURI.toURL)
        }
      })

      opt(None, "malt-model", "<file>", "malt model file", { path: String =>
        settings.maltModelFile = Some(new File(path))
      })

      opt("h", "help", "usage information", { settings.showUsage = true })

      doubleOpt(Some("t"), "threshold", "<double>", "confidence threshold for Ollie extractor", { t: Double =>
        settings.confidenceThreshold = t
      })

      doubleOpt(None, "openparse-threshold", "<double>", "confidence threshold for OpenParse component", { t: Double =>
        settings.openparseConfidenceThreshold = t
      })

      opt("p", "parallel", "execute in parallel", { settings.parallel = true })
      opt("s", "split", "split text into sentences", { settings.splitInput = true })
      opt("dependencies", "input is serialized dependency graphs (don't parse)", { settings.parseInput = false })
      opt("output-format", "specify output format from {interactive, tabbed, tabbedsingle, serialized}", { s: String => settings.outputFormat = OutputFormat.parse(s) })
      opt("ignore-errors", "ignore errors", { settings.invincible = true })
      opt("usage", "this usage message", { settings.showUsage = true })
    }

    if (argumentParser.parse(args)) {
      require(!(settings.splitInput && !settings.parseInput), "options 'split' and 'dependencies' are not compatible.")
      if (settings.showUsage) {
        println()
        println("Ollie takes sentences as input, one per line.")
        println("The response is \"confidence: extraction\", one extraction per line.")
        println(argumentParser.usage)
      } else {
        try {
          run(settings)
        }
        catch {
          case e: MalformedInputException =>
            System.err.println("\nError: a MalformedInputException was thrown.\nThis usually means there is a mismatch between what Ollie expects and the input file.  Try changing the input file's character encoding to UTF-8 or specifying the correct character encoding for the input file with '--encoding'.\n")
            e.printStackTrace()
        }
      }
    }
  }

  def run(settings: Settings) = {
    System.err.println("Loading parser models... ")
    val parser = Timing.timeThen {
      if (settings.parseInput) {
        settings.maltModelFile match {
          case None => Some(new MaltParser())
          case Some(file) => Some(new MaltParser(file))
        }
      } else None
    } { ns =>
      System.err.println(Timing.Seconds.format(ns))
    }

    System.err.print("Loading ollie models... ")
    val ollieExtractor = Timing.timeThen {
      val configuration =
        new OpenParse.Configuration(
          confidenceThreshold = settings.openparseConfidenceThreshold)

      val openparse = OpenParse.fromModelUrl(settings.modelUrl, configuration)
      new Ollie(openparse)
    } { ns =>
      System.err.println(Timing.Seconds.format(ns))
    }

    System.err.print("Loading ollie confidence function... ")
    val confFunction = Timing.timeThen {
      settings.confidenceModelUrl.map(url => OllieConfidenceFunction.fromUrl(OllieFeatureSet, url))
    } { ns =>
      System.err.println(Timing.Seconds.format(ns))
    }

    val sentencer = if (settings.splitInput) {
      System.err.println("Prose input split by OpenNlpSentencer.");
      Some(new OpenNlpSentencer()) 
    } else {
      if (settings.inputFiles.isDefined) {
        System.err.println()
        System.err.println("WARNING: Each line is expected to be a unique sentence.")
        System.err.println("If you want prose to be split into sentences, restart Ollie with --prose.")
      }
      None
    }

    using(settings.outputFile match {
      case Some(output) => new PrintWriter(output, settings.encoding)
      case None => new PrintWriter(new OutputStreamWriter(System.out, settings.encoding))
    }) { writer =>

      // print headers for output
      settings.outputFormat.header match {
        case Some(header) => writer.println(header)
        case None =>
      }

      // process a source and output extractions
      def processSource(source: Source) {
        val ns = Timing.time {
          // print prompt if standard input
          if (!settings.inputFiles.isDefined) {
            System.out.print("> ")
            System.out.flush()
          }

          val lines = parseLines(source.getLines, sentencer) filter (!_.isEmpty)
          // group the lines so we can parallelize
          val grouped = if (settings.parallel) lines.grouped(CHUNK_SIZE) else lines.map(Seq(_))
          for (group <- grouped) {

            // potentially transform to a parallel collection
            val sentences = if (settings.parallel) group.par else group
            for (sentence <- sentences) {
              try {
                if (settings.outputFormat == InteractiveFormat) {
                  writer.println(sentence)
                  writer.flush()
                }

                // parse the sentence
                val graph =
                  parser.map(_.dependencyGraph(sentence)).getOrElse(DependencyGraph.deserialize(sentence))

                // extract sentence and compute confidence
                val extrs = ollieExtractor.extract(graph).iterator.map(extr => (confFunction.map(_.getConf(extr)).getOrElse(0.0), extr))

                extrs match {
                  case it if it.isEmpty && settings.outputFormat == InteractiveFormat => writer.println("No extractions found.")
                  case it if it.isEmpty =>
                  case extrs => (extrs filter (_._1 >= settings.confidenceThreshold)).toList.sortBy(-_._1).foreach {
                    case (conf, e) =>
                      writer.println(settings.outputFormat.format(conf, e))
                      writer.flush()
                  }
                }

                if (settings.outputFormat == InteractiveFormat) {
                  writer.println()
                  writer.flush()
                }
              } catch {
                case e: Exception if settings.invincible => e.printStackTrace
              }
            }

            // print prompt if standard input
            if (!settings.inputFiles.isDefined) {
              System.out.print("> ")
              System.out.flush()
            }
          }
        }

        System.err.println()
        System.err.println("Completed in " + Timing.Seconds.format(ns) + " seconds")
      }

      settings.inputFiles match {
        // single file
        case Some(Seq(file)) =>
          System.err.println("\nRunning extractor on " + file + "...")
          using (Source.fromFile(file, settings.encoding)) { source =>
            processSource(source)
          }

        // multiple files
        case Some(files) =>
          System.err.println("\nRunning extractor on multiple files...")
          val ns = Timing.time {
            for ((file, i) <- files.iterator.zipWithIndex) {
              System.err.println("Processing file " + file + " (" + (i+1) + "/" + files.size + ")...")
              System.err.println()
              using(Source.fromFile(file, settings.encoding)) { source =>
                processSource(source)
              }
            }
          }
          System.err.println("All files completed in " + Timing.Seconds.format(ns) + " seconds")

        // standard input
        case None =>
          System.err.println("\nRunning extractor on standard input...")
          processSource(Source.fromInputStream(System.in, settings.encoding))
      }
    }
  }

  def parseLines(lines: Iterator[String], sentencer: Option[Segmenter]) = {
    sentencer match {
      case None => lines
      case Some(sentencer) => new SentenceIterator(sentencer, lines.buffered)
    }
  }
}
