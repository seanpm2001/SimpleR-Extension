package org.nlogo.extensions.simpler

import com.fasterxml.jackson.core.JsonParser
import org.json4s.jackson.JsonMethods.mapper

import org.nlogo.languagelibrary.Subprocess
import org.nlogo.languagelibrary.config.{ Config, Menu }
import org.nlogo.api.{ Argument, Command, Context, DefaultClassManager, ExtensionException, ExtensionManager, FileIO, PrimitiveManager, Reporter }
import org.nlogo.core.Syntax

import java.io.File
import java.net.ServerSocket

object RExtension {
  val codeName   = "sr"
  val longName   = "SimpleR Extension"
  val extLangBin = "Rscript"

  var menu: Option[Menu] = None
  val config: Config     = Config.createForPropertyFile(classOf[RExtension], RExtension.codeName)

  private var _rProcess: Option[Subprocess] = None

  def rProcess: Subprocess =
    _rProcess.getOrElse(throw new ExtensionException(
      "R process has not been started. Please run sr:setup first before any other SimpleR extension primitive"
      ))

  def rProcess_=(proc: Subprocess): Unit = {
    _rProcess.foreach(_.close())
    _rProcess = Some(proc)
  }

  def killR(): Unit = {
    _rProcess.foreach(_.close())
    _rProcess = None
  }

}

class RExtension extends DefaultClassManager {
  def load(manager: PrimitiveManager): Unit = {
    manager.addPrimitive("setup", SetupR)
    manager.addPrimitive("run", Run)
    manager.addPrimitive("runresult", RunResult)
    manager.addPrimitive("set", Set)
  }

  override def runOnce(em: ExtensionManager): Unit = {
    super.runOnce(em)
    mapper.configure(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS, true)

    RExtension.menu = Menu.create(RExtension.longName, RExtension.extLangBin, RExtension.config)
  }

  override def unload(em: ExtensionManager): Unit = {
    super.unload(em)
    RExtension.killR()
    RExtension.menu.foreach(_.unload())
  }

}

object SetupR extends Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(right = List())

  override def perform(args: Array[Argument], context: Context): Unit = {
    val dummySocket = new ServerSocket(0);
    val port = dummySocket.getLocalPort
    dummySocket.close()

    val rExtensionDirectory = Config.getExtensionRuntimeDirectory(classOf[RExtension], RExtension.codeName)
    // see docs in `rlibs.R` for what this is about
    val maybeRLibFile       = new File(rExtensionDirectory, "rlibs.R")
    val rLibFile            = if (maybeRLibFile.exists) { maybeRLibFile } else { (new File("rlibs.R")).getCanonicalFile }
    val rLibFilePath        = rLibFile.toString
    val maybeRExtFile       = new File(rExtensionDirectory, "rext.R")
    val rExtFile            = if (maybeRExtFile.exists) { maybeRExtFile } else { (new File("rext.R")).getCanonicalFile }
    val rExtFilePath        = rExtFile.toString
    val maybeRRuntimePath   = Config.getRuntimePath(
        RExtension.extLangBin
      , RExtension.config.runtimePath.getOrElse("")
      , "--version"
    )
    val rRuntimePath = maybeRRuntimePath.getOrElse(
      throw new ExtensionException(s"We couldn't find an R executable file to run.  Please make sure R is installed on your system.  Then you can tell the ${RExtension.longName} where it's located by opening the SimplerR Extension menu and selecting Configure to choose the location yourself or putting making sure ${RExtension.extLangBin} is available on your PATH.\n")
    )
    val rExtUserDirPath = FileIO.perUserDir(RExtension.codeName)

    try {
      // see docs in `rlibs.R` for what this is about
      import scala.sys.process._
      Seq(rRuntimePath, rLibFilePath, rExtUserDirPath).!
      RExtension.rProcess = Subprocess.start(context.workspace,
        Seq(rRuntimePath),
        Seq(rExtFilePath, port.toString, rExtUserDirPath),
        RExtension.codeName,
        RExtension.longName,
        Some(port))
      RExtension.menu.foreach(_.setup(RExtension.rProcess.evalStringified))
    } catch {
      case e: Exception => {
        println(e)
        throw new ExtensionException(s"""The ${RExtension.longName} didn't want to start.  Make sure you are using version 4 of R.  You can also try to manually install the rjson package is installed for use by R: `install.packages("rjson", repos = "http://cran.us.r-project.org", quiet = TRUE)`.""", e)
      }
    }
  }
}

object Run extends Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(
    right = List(Syntax.StringType | Syntax.RepeatableType)
  )

  override def perform(args: Array[Argument], context: Context): Unit =
    RExtension.rProcess.exec(args.map(_.getString).mkString("\n"))
}

object RunResult extends Reporter {
  override def getSyntax: Syntax = Syntax.reporterSyntax(
    right = List(Syntax.StringType),
    ret = Syntax.WildcardType
  )

  override def report(args: Array[Argument], context: Context): AnyRef =
    RExtension.rProcess.eval(args.map(_.getString).mkString("\n"))
}

object Set extends Command {
  override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.StringType, Subprocess.convertibleTypesSyntax))
  override def perform(args: Array[Argument], context: Context): Unit =
    RExtension.rProcess.assign(args(0).getString, args(1).get)
}
