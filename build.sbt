javaHome := {
  var s = System.getenv("JAVA_HOME")
  if (s==null) {
    // tbd. try to detect JDK location on multiple platforms
    //
    // OS X: "/Library/Java/JavaVirtualMachines/jdk1.xxx.jdk/Contents/Home" with greatest id (i.e. "7.0_10")
    //
    s= "/Library/Java/JavaVirtualMachines/jdk1.7.0_10.jdk/Contents/Home"
  }
  //
  val dir = new File(s)
  if (!dir.exists) {
    throw new RuntimeException( "No JDK found - try setting 'JAVA_HOME'." )
  }
  //
  Some(dir)  // 'sbt' 'javaHome' value is ': Option[java.io.File]'
}

unmanagedJars in Compile <+= javaHome map { jh /*: Option[File]*/ =>
  val dir: File = jh.getOrElse(null)    // unSome
  //
  val jfxJar = new File(dir, "/jre/lib/jfxrt.jar")
  if (!jfxJar.exists) {
    throw new RuntimeException( "JavaFX not detected (needs Java runtime 7u06 or later): "+ jfxJar.getPath )  // '.getPath' = full filename
  }
  Attributed.blank(jfxJar)
} 

libraryDependencies += "com.google.zxing" % "core" % "3.1.0"