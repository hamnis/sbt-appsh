libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

addSbtPlugin("no.arktekk.sbt" % "aether-deploy" % "0.21")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.0")
