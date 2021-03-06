package appmgr

import sbt._
import sbt.io.Using
import Keys._
import archiver.{FilePermissions, FileMapping, Archiver, Packaging}


object AppmgrPlugin extends AutoPlugin {
  val defaultBinPermissions = FilePermissions(Integer.decode("0755")).getOrElse(sys.error("Invalid permissions"))

  override def trigger = allRequirements

  override def requires = plugins.CorePlugin

  object autoImport {

    val Appmgr = config("appmgr")

    val appmgrPermissions = Def.settingKey[Map[String, FilePermissions]]("Map from path to unix permissions")
    val appmgrLauncher = Def.settingKey[Option[Launcher]]("""|Default Launcher:
      |can be loaded from classpath by adding a classpath:/path/to/launcher.
      |or from file by using file:/path/to/file.
      |The file can expect a few config parameters:
      | - launcher.command - command to run
      | - launcher.name - Name of the app
      | - launcher.description - Short desciption of program
      |
      |The app.config file will be auto-generated if this is set.
      |The auto-generated file will be merged any existing app.config file.
      |
      |The default implementation will expect a jvm program,
      |so we will register a JAVA_OPTS and a JVM_OPT environment variable.
      |
      |Config variables registered in the 'app.name' config group
      |will be passed on as system properties.
      |
      |Example app.config:
      | app.launcher=bin/launcher.sh
      | launcher.command=main
      | launcher.name=foo
      | launcher.desciption=Foo program
      | foo.server=example.com
      |
      """.stripMargin)

    def overrideLauncherCommand(cmd: String) = {
      appmgrLauncher in Appmgr := (appmgrLauncher in Appmgr).value.map(_.copy(command = cmd))
    }

    def overrideLauncherKey(_name: String) = {
      appmgrLauncher in Appmgr := (appmgrLauncher in Appmgr).value.map(_.copy(name = _name))
    }

    def appmgrAttach(classifier: String = "appmgr") = attach(packageBin in Appmgr, Appmgr, classifier, "zip")
  }


  
  import autoImport._

  override def projectSettings = inConfig(Appmgr)(Seq(
    sourceDirectory := baseDirectory.value / "src" / "appmgr",
    managedDirectory := (target in Compile).value / "appmgr",
    target := (target in Compile).value / "appmgr.zip",
    appmgrPermissions := Map(
      "root/bin/*" -> defaultBinPermissions ,
      "hooks/*" -> defaultBinPermissions
    ),
    appmgrLauncher := {
      Some(Launcher(Resource.DefaultLauncher, "main", name.value, description.value))
    },
    packageBin := {
      val zip = target.value
      val stream = streams.value
      if (zip.exists) IO.delete(zip)
      IO.withTemporaryDirectory{ temp =>
        val mapping = FileMapping(List(managedDirectory.value, sourceDirectory.value), permissions = appmgrPermissions.value)
        val launcherM = handleLauncher(appmgrLauncher.value, mapping, temp)
        val real = mapping.append(launcherM)
        validate(mapping)
        val archiver = Archiver(Packaging(zip))
        val file = archiver.create(real, zip)
        stream.log.info("Created appmgr app in: " + file)
        file
      }
    },
    Keys.`package` := packageBin.value
  ))

  def attach(task: TaskKey[File], conf: Configuration, classifier: String, extension: String = "jar"): Seq[Setting[_]] = {
    Seq(
      artifact in (Compile, task) := {
      val art = (artifact in (Compile, task)).value
      art.withClassifier(Some(classifier)).withType(extension).withExtension(extension)
    }) ++ addArtifact(artifact in (conf, task), task)
  }

  def handleLauncher(launcher: Option[Launcher], mapping: FileMapping, directory: File): FileMapping = {
    import collection.JavaConverters._

    def config(l: Launcher) = {
      val config = mapping.mappings.get("app.config")
      val configMap = config.foldLeft(new java.util.Properties()){case (p, f) => 
        val is = new java.io.FileInputStream(f)
        p.load(is)
        is.close()
        p
      }.asScala.toMap
      val c = if (!configMap.contains("app.launcher")) configMap ++ l.asMap else configMap
      c.map{case (k,v) => s"$k=$v"}.mkString("", "\n", "\n")
    }

    val map = launcher match {
      case Some(l) => {
        val target = directory / "launcher.sh"
        Using.urlInputStream(l.launcher.asURL) { inputStream =>
          IO.transfer(inputStream, target)
        }

        val configFile = directory / "app.config"       
        IO.write(configFile, config(l))
        val m = Map(
          "root/bin/launcher.sh" -> target,
          "app.config" -> configFile
        )
        FileMapping(m, Map("root/bin/launcher.sh" -> defaultBinPermissions))
      }
      case None => FileMapping(Map.empty[String, File], Map.empty[String, FilePermissions]) 
    }
    map
  }

  def negate[A](p: (A) => Boolean) = (a: A) => !p(a)

  def validate(mapping: FileMapping) {
    val fileMap = mapping.mappings
    val config = fileMap.get("app.config")
    val bin = fileMap.get("root/bin")
    val postInstall = fileMap.get("hooks/post-install")
    
    val fileExists = (c: File) => c.exists && c.isFile
    val dirExists = (c: File) => c.exists && c.isDirectory

    if (!config.forall(fileExists)) {
      sys.error("Missing app.config file")
    }

    if (!bin.forall(dirExists)) {
      sys.error("Not a valid apps.sh directory, please make sure there is a 'root/bin' directory.")
      if (!fileMap.exists{case (n, f) => n.startsWith("root/bin/") && fileExists(f) }) {
        sys.error("No executables found in root/bin")
      }
    }

    if (!postInstall.forall(negate(fileExists))) {
        sys.error("'hooks/post-install' does not exist or is not a file")
    }
  }
}
