import org.freecompany.redline.Builder
import org.freecompany.redline.header.Architecture
import org.freecompany.redline.header.Os
import org.freecompany.redline.header.RpmType
import org.freecompany.redline.payload.Directive
import tablesaw.*
import tablesaw.addons.GZipRule
import tablesaw.addons.TarRule
import tablesaw.addons.ivy.IvyAddon
import tablesaw.addons.ivy.PomRule
import tablesaw.addons.ivy.PublishRule
import tablesaw.addons.java.Classpath
import tablesaw.addons.java.JarRule
import tablesaw.addons.java.JavaCRule
import tablesaw.addons.java.JavaProgram
import tablesaw.addons.junit.JUnitRule
import tablesaw.definitions.Definition
import tablesaw.rules.CopyRule
import tablesaw.rules.DirectoryRule
import tablesaw.rules.Rule
import tablesaw.rules.SimpleRule

import javax.swing.*
import java.util.regex.Pattern

println("===============================================")

saw.setProperty(Tablesaw.PROP_MULTI_THREAD_OUTPUT, Tablesaw.PROP_VALUE_ON)

programName = saw.getProperty(JavaProgram.PROGRAM_NAME_PROPERTY)
if (programName == null)
	programName = "kairosdb"

//Do not use '-' in version string, it breaks rpm uninstall.
version = "1.3.0"
release = saw.getProperty("KAIROS_RELEASE_NUMBER", "1") //package release number
summary = "KairosDB"
description = """\
KairosDB is a time series database that stores numeric values along
with key/value tags to a nosql data store.  Currently supported
backends are Cassandra and H2.  An H2 implementation is provided
for development work.
"""

saw.setProperty(JavaProgram.PROGRAM_NAME_PROPERTY, programName)
saw.setProperty(JavaProgram.PROGRAM_DESCRIPTION_PROPERTY, description)
saw.setProperty(JavaProgram.PROGRAM_VERSION_PROPERTY, version+'-'+release)
saw.setProperty(PomRule.GROUP_ID_PROPERTY, "org.kairosdb")
saw.setProperty(PomRule.URL_PROPERTY, "http://kairosdb.org")

saw = Tablesaw.getCurrentTablesaw()
saw.includeDefinitionFile("definitions.xml")

//add -D usejdk11=true for java 11 compile
useJdk11 = saw.getProperty("usejdk11", "false").equals("true")

javaVersion = "1.8"
defaultConfig = "default"
testConfig = "test"

if (useJdk11)
{
	javaVersion = "11"
	defaultConfig = "jdk11"
	testConfig = "testjdk11"
}

ivyConfig = [defaultConfig, "integration"]


rpmDir = "build/rpm"
docsDir = "build/docs"
rpmNoDepDir = "build/rpm-nodep"
new DirectoryRule("build")
rpmDirRule = new DirectoryRule(rpmDir)
rpmNoDepDirRule = new DirectoryRule(rpmNoDepDir)

//Having a rule for the queue folder will ensure it gets removed durring a clean
new DirectoryRule("queue")

//------------------------------------------------------------------------------
//Setup java rules
ivy = new IvyAddon()
		.addSettingsFile("ivysettings.xml")

if (new File("myivysettings.xml").exists())
	ivy.addSettingsFile("myivysettings.xml")

ivy.setup()

buildLibraries = new RegExFileSet("lib", ".*\\.jar")
		.addExcludeDir("integration")
		.getFullFilePaths()

jp = new JavaProgram()
		.setLibraryJars(buildLibraries)
		.setup()

jc = jp.getCompileRule()
ivyDefaultResolve = ivy.getResolveRule(defaultConfig)
jc.addDepend(ivyDefaultResolve)




jc.getDefinition().set("target", javaVersion)
jc.getDefinition().set("source", javaVersion)
jc.getDefinition().set("encoding", "UTF8")
jc.getDefinition().set("deprecation")
jc.getDefinition().set("unchecked")

jp.getJarRule().addFiles("src/main/resources", "kairosdb.conf")
jp.getJarRule().addFiles("src/main/resources", "create.sql")

def configurePomRule(PomRule pomRule)
{
	pomRule.addDepend("ivy.xml")
			.addDepend("ivysettings.xml")
			.setJavaVersion(javaVersion)
			.addLicense("The Apache Software License, Version 2.0", "http://www.apache.org/licenses/LICENSE-2.0.txt", "repo")
			.addDeveloper("brianhks", "Brian", "brianhks1+kairos@gmail.com")
			.addDeveloper("jeff", "Jeff", "jeff.sabin+kairos@gmail.com")

	return pomRule;
}

//------------------------------------------------------------------------------
//==-- Generate Project Pom --==
configurePomRule(ivy.createPomRule("pom.xml", ivy.getResolveRule(defaultConfig), ivy.getResolveRule(testConfig)))
		.setName("project-pom")
		.setDescription("Use this target to generate a pom used for opening project in IDE")

//------------------------------------------------------------------------------
//==-- Maven POM Rule --==
pomRule = configurePomRule(ivy.createPomRule("build/jar/pom.xml", ivy.getResolveRule(defaultConfig)))
		.addDepend(jp.getJarRule())


//------------------------------------------------------------------------------
//==-- Publish Artifacts --==
if (version.contains("SNAPSHOT"))
	defaultResolver = "local-m2-publish-snapshot"
else
	defaultResolver = "local-m2-publish"
PublishRule publishRule = ivy.createPublishRule(saw.getProperty("ivy.publish_resolver", defaultResolver),
			ivy.getResolveRule(defaultConfig))
		.setName("publish")
		.setDescription("Publish pom and jar to maven snapshot repo")
		.publishMavenMetadata()
		.setOverwrite(true)

publishRule.addArtifact(pomRule.getTarget())
		.setType("pom")
		.setExt("pom")
		.setIsMetadata()
publishRule.addArtifact(jp.getJarRule().getTarget())
		.setType("jar")
		.setExt("jar")


//------------------------------------------------------------------------------
//Set information in the manifest file
manifest = jp.getJarRule().getManifest().getMainAttributes()
manifest.putValue("Manifest-Version", "1.0")
manifest.putValue("Tablesaw-Version", saw.getVersion())
manifest.putValue("Created-By", saw.getProperty("java.vm.version")+" ("+
			saw.getProperty("java.vm.vendor")+")")
manifest.putValue("Built-By", saw.getProperty("user.name"))
buildDateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z")
manifest.putValue("Build-Date", buildDateFormat.format(new Date()))

buildNumberFormat = new java.text.SimpleDateFormat("yyyyMMddHHmmss")
buildNumber = buildNumberFormat.format(new Date())
manifest.putValue("Implementation-Title", "KairosDB")
manifest.putValue("Implementation-Vendor", "KairosDB")
manifest.putValue("Implementation-Version", "${version}-${release}.${buildNumber}")

//Add git revision information
gitRevisionFile= ".gitrevision"
new File(gitRevisionFile).text = ""
ret = saw.exec(null, "git rev-parse HEAD", false, null, gitRevisionFile)
revision = new File(gitRevisionFile).text.trim()
new File(gitRevisionFile).delete()
if (ret == 0)
	manifest.putValue("Git-Revision", revision)


//------------------------------------------------------------------------------
//Setup unit tests
testClasspath = new Classpath(jp.getLibraryJars())
testClasspath.addPath(jp.getJarRule().getTarget())


testSources = new RegExFileSet("src/test/java", ".*Test\\.java").recurse()
		.addExcludeFiles("CassandraDatastoreTest.java")
		.getFilePaths()
testCompileRule = jp.getTestCompileRule()
ivyTestResolve = ivy.getResolveRule(testConfig)
testCompileRule.addDepend(ivyTestResolve)
testCompileRule.getDefinition().set("unchecked")
testCompileRule.getDefinition().set("deprecation")
//testCompileRule.getDefinition().set("verbose")

new SimpleRule("compile-test").addDepend(testCompileRule)

junitClasspath = new Classpath(testCompileRule.getClasspath())
junitClasspath.addPaths(testClasspath)
junitClasspath.addPath("src/main/java")
junitClasspath.addPath("src/test/resources")
junitClasspath.addPath("src/main/resources")

junit = new JUnitRule("test").addSources(testSources)
		.setClasspath(junitClasspath)
		.addDepends(testCompileRule)
		.addDepends(ivyTestResolve)
		.addJvmArgument("-Duser.timezone=UTC")

if (saw.getProperty("jacoco", "false").equals("true"))
	junit.addJvmArgument("-javaagent:lib_test/jacocoagent.jar=destfile=build/jacoco.exec")

testSourcesAll = new RegExFileSet("src/test/java", ".*Test\\.java").recurse().getFilePaths()
junitAll = new JUnitRule("test-all").setDescription("Run unit tests including Cassandra tests - requires Cassandra running on localhost")
		.addSources(testSourcesAll)
		.setClasspath(junitClasspath)
		.addDepends(testCompileRule)
		.addDepends(ivyTestResolve)
		.addJvmArgument("-Duser.timezone=UTC")

if (saw.getProperty("jacoco", "false").equals("true"))
	junitAll.addJvmArgument("-javaagent:lib_test/jacocoagent.jar=destfile=build/jacoco.exec")

//------------------------------------------------------------------------------
//Build zip deployable application
rpmFile = "$programName-$version-${release}.rpm"
srcRpmFile = "$programName-$version-${release}.src.rpm"
ivyFileSet = new SimpleFileSet()

//Resolve dependencies for package
ivyResolve = ivy.getResolveRule(defaultConfig)
resolveIvyFileSetRule = new SimpleRule()
		.addDepend(ivyResolve)
		.setMakeAction("doIvyResolve")
		.alwaysRun()

def doIvyResolve(Rule rule)
{
	classpath = ivyResolve.getClasspath()

	for (String jar in classpath.getPaths())
	{
		file = new File(jar)
		ivyFileSet.addFile(file.getParent(), file.getName())
	}
}

//Check for plugins config
buildPlugins = new HashMap()
propertyKeys = saw.getProperties().keySet()
for (String key in propertyKeys)
{
	if (key.startsWith("kairos.build_plugin."))
	{
		plugin = key.substring(20)
		buildPlugins.put(plugin, new RegExFileSet(saw.getProperty(key), ".*\\.jar"))
	}
}

libFileSets = [
		new RegExFileSet("build/jar", ".*\\.jar"),
		new RegExFileSet("lib", ".*\\.jar"),
		ivyFileSet
	]

scriptsFileSet = new RegExFileSet("src/scripts", ".*").addExcludeFile("kairosdb-env.sh")
webrootFileSet = new RegExFileSet("webroot", ".*").recurse()
authFileSet = new RegExFileSet("src/main/conf/auth", ".*")

zipLibDir = "$programName/lib"
zipBinDir = "$programName/bin"
zipConfDir = "$programName/conf"
zipConfLoggingDir = "$zipConfDir/logging"
zipWebRootDir = "$programName/webroot"
zipAuthDir = "$programName/conf/auth"
tarRule = new TarRule("build/${programName}-${version}-${release}.tar")
		.addDepend(jp.getJarRule())
		.addDepend(resolveIvyFileSetRule)
		.addFileSetTo(zipBinDir, scriptsFileSet)
		.addFileSetTo(zipWebRootDir, webrootFileSet)
		.addFileSetTo(zipAuthDir, authFileSet)
		.addFileTo(zipConfDir, "src/main/resources", "kairosdb.conf")
		.addFileTo(zipConfLoggingDir, "src/main/resources", "logback.xml")
		.setFilePermission(".*\\.sh", 0755)

for (AbstractFileSet fs in libFileSets)
	tarRule.addFileSetTo(zipLibDir, fs)


gzipRule = new GZipRule("package").setSource(tarRule.getTarget())
		.setDescription("Create deployable tar file")
		.setTarget("build/${programName}-${version}-${release}.tar.gz")
		.addDepend(tarRule)

//------------------------------------------------------------------------------
//Build rpm file
rpmBaseInstallDir = "/opt/$programName"
targetServiceFile = "build/kairosdb.service"
serviceFileRule = new SimpleRule()
		.addTarget(targetServiceFile)
		.addSource("src/scripts/kairosdb.service")
		.setMakeAction("copyServiceFile")
def copyServiceFile(Rule rule)
{
	String content = new File(rule.source).text
	new File(rule.target).write(content.replaceAll("BASE_INSTALL_DIR", rpmBaseInstallDir))
}

rpmRule = new SimpleRule("package-rpm").setDescription("Build RPM Package")
		.addDepend(jp.getJarRule())
		.addDepend(resolveIvyFileSetRule)
		.addDepend(rpmDirRule)
		.addDepend(serviceFileRule)
		.addTarget("$rpmDir/$rpmFile")
		.setMakeAction("doRPM")
		.setProperty("dependency", "on")

new SimpleRule("package-rpm-nodep").setDescription("Build RPM Package with no dependencies")
		.addDepend(jp.getJarRule())
		.addDepend(resolveIvyFileSetRule)
		.addDepend(rpmNoDepDirRule)
		.addDepend(serviceFileRule)
		.addTarget("${rpmNoDepDir}/$rpmFile")
		.setMakeAction("doRPM")

def doRPM(Rule rule)
{
	//Build rpm using redline rpm library
	host = InetAddress.getLocalHost().getHostName()
	rpmBuilder = new Builder()
	rpmBuilder.with
			{
				description = description
				group = "System Environment/Daemons"
				license = "license"
				setPackage(programName, version, release)
				setPlatform(Architecture.NOARCH, Os.LINUX)
				summary = summary
				type = RpmType.BINARY
				url = "http://kairosdb.org"
				vendor = "KairosDB"
				provides = programName
				//prefixes = rpmBaseInstallDir
				buildHost = host
				sourceRpm = srcRpmFile
			}

	if ("on".equals(rule.getProperty("dependency")))
		rpmBuilder.addDependencyMore("jre", javaVersion)

	rpmBuilder.setPostInstallScript(new File("src/scripts/install/post_install.sh"))
	rpmBuilder.setPreUninstallScript(new File("src/scripts/install/pre_uninstall.sh"))

	for (AbstractFileSet fs in libFileSets)
		addFileSetToRPM(rpmBuilder, "$rpmBaseInstallDir/lib", fs)

	//Add any plugins to build
	buildPlugins.each { plugin, jars ->
		addFileSetToRPM(rpmBuilder, "$rpmBaseInstallDir/lib/$plugin", jars)
	}

	addFileSetToRPM(rpmBuilder, "$rpmBaseInstallDir/bin", scriptsFileSet)

	//rpmBuilder.addFile("/etc/init.d/kairosdb", new File("src/scripts/kairosdb-service.sh"), 0755)
	rpmBuilder.addFile("/lib/systemd/system/kairosdb.service", new File(serviceFileRule.getTarget()), 0644)
	rpmBuilder.addFile("$rpmBaseInstallDir/conf/kairosdb.conf",
			new File("src/main/resources/kairosdb.conf"), 0644, new Directive(Directive.RPMFILE_CONFIG | Directive.RPMFILE_NOREPLACE))
	rpmBuilder.addFile("$rpmBaseInstallDir/conf/logging/logback.xml",
			new File("src/main/resources/logback.xml"), 0644, new Directive(Directive.RPMFILE_CONFIG | Directive.RPMFILE_NOREPLACE))
	rpmBuilder.addFile("$rpmBaseInstallDir/bin/kairosdb-env.sh",
			new File("src/scripts/kairosdb-env.sh"), 0755, new Directive(Directive.RPMFILE_CONFIG | Directive.RPMFILE_NOREPLACE))

	for (AbstractFileSet.File f : webrootFileSet.getFiles())
		rpmBuilder.addFile("$rpmBaseInstallDir/webroot/$f.file", new File(f.getBaseDir(), f.getFile()))

	println("Building RPM "+rule.getTarget())
	outputFile = new FileOutputStream(rule.getTarget())
	rpmBuilder.build(outputFile.channel)
	outputFile.close()
}

def addFileSetToRPM(Builder builder, String destination, AbstractFileSet files)
{

	for (AbstractFileSet.File file : files.getFiles())
	{
		File f = new File(file.getBaseDir(), file.getFile())
		if (f.getName().endsWith(".sh"))
			builder.addFile(destination + "/" +file.getFile(), f, 0755)
		else
			builder.addFile(destination + "/" + file.getFile(), f)
	}
}

debRule = new SimpleRule("package-deb").setDescription("Build Deb Package")
		.addDepend(rpmRule)
		.setMakeAction("doDeb")

def doDeb(Rule rule)
{
	//Prompt the user for the sudo password
	//TODO: package using jdeb
	def jpf = new JPasswordField()
	def password = saw.getProperty("sudo")

	if (password == null)
	{
		def resp = JOptionPane.showConfirmDialog(null,
				jpf, "Enter sudo password:",
				JOptionPane.OK_CANCEL_OPTION)

		if (resp == 0)
			password = jpf.getPassword()
	}

	if (password != null)
	{
		sudo = saw.createAsyncProcess(rpmDir, "sudo -S alien --bump=0 --to-deb $rpmFile")
		sudo.run()
		//pass the password to the process on stdin
		sudo.sendMessage("$password\n")
		sudo.waitForProcess()
		if (sudo.getExitCode() != 0)
			throw new TablesawException("Unable to run alien application")
	}
}


//------------------------------------------------------------------------------
//Run the Kairos application
new SimpleRule("run-debug").setDescription("Runs kairosdb so a debugger can attach to port 5005")
		.addDepends(jp.getJarRule())
		.setMakeAction("doRun")
		.setProperty("DEBUG", true)

new SimpleRule("run").setDescription("Runs kairosdb")
		.addDepends(jp.getJarRule())
		.setMakeAction("doRun")
		.setProperty("DEBUG", false)
new SimpleRule("export").setDescription("Exports metrics from KairosDB." +
		"\n\t-D f <filename> - file to write output to. If not specified, the output goes to stdout." +
		"\n\t-D n <metricName> - name of metric to export. If not specified, then all metrics are exported.")
		.addDepends(jp.getJarRule())
		.setMakeAction("doRun")
		.setProperty("ACTION", "export")
new SimpleRule("import").setDescription("Imports metrics." +
		"\n\t-D f <filename> to specify output file. If not specified the input comes from stdin.")
		.addDepends(jp.getJarRule())
		.setMakeAction("doRun")
		.setProperty("ACTION", "import")

def doRun(Rule rule)
{
	kairosDefinition = saw.getDefinition("kairos")

	if (rule.getProperty("ACTION") == "export")
	{
		kairosDefinition.set("command", "export")
		metricName = saw.getProperty("n", "")
		if (metricName.length() > 0)
			kairosDefinition.set("export_metric", metricName)
		filename = saw.getProperty("f", "")
		if (filename.length() > 0)
			kairosDefinition.set("import_export_file", filename)
	}
	else if (rule.getProperty("ACTION") == "import")
	{
		kairosDefinition.set("command", "import")
		filename = saw.getProperty("f", "")
		if (filename.length() > 0)
			kairosDefinition.set("import_export_file", filename)
	}
	else
		kairosDefinition.set("command", "run")

	//Check if you have a custom kairosdb.properties file and load it.
	customConf = new File("kairosdb.conf")
	customProps = new File("kairosdb.properties")
	if (customConf.exists())
		kairosDefinition.set("properties", "kairosdb.conf")
	else if (customProps.exists())
		kairosDefinition.set("properties", "kairosdb.properties")

	if (rule.getProperty("DEBUG"))
		kairosDefinition.set("debug")

	/*
	  This is for use with visual vm.  Set the startup agent command in
	  tablesaw.properties and this will inject it into the startup command
	 */
	if (saw.getProperty("profile") != null)
		kairosDefinition.set("profile", saw.getProperty("profile"))

	//this is to load logback into classpath
	runClasspath = jc.getClasspath()
	runClasspath.addPaths(ivyDefaultResolve.getClasspath())
	runClasspath.addPath("src/main/resources").addPath("src/main/java")
	kairosDefinition.set("classpath", runClasspath)
	//ret = saw.exec("java ${debug} -Dio.netty.epollBugWorkaround=true -cp ${runClasspath} org.kairosdb.core.Main ${args}", false)
	ret = saw.exec(kairosDefinition.getCommand())
	println(ret)
}


//------------------------------------------------------------------------------
//Generate GenORM Files for H2 module
genormDefinition = saw.getDefinition("genormous")
genormDefinition.set("genorm")
new SimpleRule("genorm").setDescription("Generate ORM files")
		.addDepend(ivy.getResolveRule("build_tools"))
		.setMakeAction("doGenorm")

def doGenorm(Rule rule)
{
	resolve = ivy.getResolveRule("build_tools")

	genormClasspath = new Classpath(resolve.getClasspath())
	genormDefinition.set("classpath", genormClasspath.toString())
	genormDefinition.set("source", "src/main/conf/tables.xml")
	cmd = genormDefinition.getCommand()
	saw.exec(cmd)
}


//------------------------------------------------------------------------------
//Build Integration tests
integrationClassPath = new Classpath(jp.getLibraryJars())
//.addPaths(new RegExFileSet("lib/ivy/integration", ".*\\.jar").getFullFilePaths())
		.addPath("src/integration-test/resources")

ivyIntegrationRule = ivy.getResolveRule("integration")

integrationBuildRule = new JavaCRule("build/integration")
		.addSourceDir("src/integration-test/java")
		.addClasspath(integrationClassPath)
		.addDepend(ivyIntegrationRule)

new SimpleRule("integration")
		.setMakeAction("doIntegration")
		.addDepend(integrationBuildRule)
		.addDepend(ivyIntegrationRule)

def doIntegration(Rule rule)
{
	integrationClassPath.addPaths(ivyIntegrationRule.getClasspath())
	integrationClassPath.addPath("build/integration")
	host = saw.getProperty("host", "127.0.0.1")
	port = saw.getProperty("port", "8080")
	saw.exec("java -Duser.timezone=UTC -Dhost=${host} -Dport=${port} -cp ${integrationClassPath} org.testng.TestNG src/integration-test/testng.xml")
}

//------------------------------------------------------------------------------
//Build Docs
new SimpleRule("docs").setDescription("Build Sphinx Documentation")
		.setMakeAction("doDocs")
		.addSources(new RegExFileSet("src/docs", ".*").recurse().getFullFilePaths())
		.setProperty("all", false)

new SimpleRule("docs-rebuild").setDescription("Rebuild Sphinx Documentation. All docs are built even if not changed.")
		.setMakeAction("doDocs")
		.addSources(new RegExFileSet("src/docs", ".*").recurse().getFullFilePaths())
		.setProperty("all", true)

def doDocs(Rule rule)
{
    command = "sphinx-build"
    if (rule.getProperty("all"))
        command += " -a"
    sudo = saw.createAsyncProcess(".", "${command} -b html src/docs ${docsDir}")
    sudo.run()
    sudo.waitForProcess()
    if (sudo.getExitCode() != 0)
        throw new TablesawException("Unable to run sphinx-build")
}


//---------------------------------------------------------------------------
//Build Docker container
dockerBuild = new SimpleRule("docker-build").setDescription("Build a Docker image, can specify docker registry with -D DOCKER_REGISTRY=<registry>")
		.addDepend(tarRule)
		.setMakeAction("doDockerBuild")

def doDockerBuild(Rule rule)
{
	def tag = getDockerTag()
	command = "docker build -t ${tag} --build-arg VERSION=${version}-${release} ."
	saw.exec(command)
}

def getDockerTag()
{
	def registry = saw.getProperty("DOCKER_REGISTRY", "");

	if ( registry != "" ) {
		registry += "/"
	}

	return registry + "${programName}:${version}-${release}"
}

//------------------------------------------------------------------------------
// Push container
new SimpleRule("docker-push").setDescription("Push a Docker image to registry, can specify docker registry with -D DOCKER_REGISTRY=<registry>")
		.addDepend(dockerBuild)
		.setMakeAction("doDockerPush")

def doDockerPush(Rule rule)
{
	def tag = getDockerTag()
	command = "docker push ${tag}"
	saw.exec(command)
}

//------------------------------------------------------------------------------
//==-- Maven Artifacts --==
bundleDir = new DirectoryRule("build/bundle")
copyBundleBits = new CopyRule()
		.addDepend(bundleDir)
		.addDepend(gzipRule)
		.addDepend(jp.getJarRule())
		.addDepend(jp.getJavaDocJarRule())
		.addDepend(jp.getSourceJarRule())
		.addDepend(pomRule)
		.addFile(gzipRule.getTarget())
		.addFile(jp.getJarRule().getTarget())
		.addFile(jp.getJavaDocJarRule().getTarget())
		.addFile(jp.getSourceJarRule().getTarget())
		.addFile("build/jar/pom.xml")
		.setDestination("build/bundle")

mavenArtifactsRule = new SimpleRule("maven-artifacts").setDescription("Create maven artifacts for maven central")
		.addDepend(copyBundleBits)

		.setMakeAction("signArtifacts")

void signArtifacts(Rule rule)
{
	for (String source : new RegExFileSet("build/bundle", ".*").getFullFilePaths())
	{
		cmd = "gpg2 -ab "+source
		saw.exec(cmd)
	}
}

new JarRule("maven-bundle", "build/bundle.jar").setDescription("Create bundle for uploading to maven central")
		.addDepend(mavenArtifactsRule)
		.addFileSet(new RegExFileSet("build/bundle", ".*"))



saw.setDefaultTarget("jar")

//==============================================================================
//== Modernizer plugin ==

//Resolve dependencies for package
modernizeResolve = ivy.getResolveRule("build_tools")
new SimpleRule("modernize").setDescription("Run maven modernizer plugin on class files")
		.addDepend(modernizeResolve)
		.addDepend(jc)
		.setMakeAction("doModernizeResolve")
		.alwaysRun()

def doModernizeResolve(Rule rule)
{
	classpath = modernizeResolve.getClasspath()

	for (String jar in classpath.getPaths())
	{
		saw.addClasspath(jar)
	}

	saw.getScriptInterpreter().source("modernizer.groovy")
	//saw.include("modernizer.groovy")

}



//------------------------------------------------------------------------------
//Build notification
def printMessage(String title, String message) {
	osName = saw.getProperty("os.name")

	Definition notifyDef
	if (osName.startsWith("Linux"))
	{
		notifyDef = saw.getDefinition("linux-notify")
	}
	else if (osName.startsWith("Mac"))
	{
		notifyDef = saw.getDefinition("mac-notify")
	}
	else if (osName.startsWith("Windows"))
	{
		notifyDef = saw.getDefinition("windows-notify")
	}

	if (notifyDef != null)
	{
		notifyDef.set("title", title)
		notifyDef.set("message", message)
		saw.exec(notifyDef.getCommand())
	}
}

def buildFailure(Exception e)
{
	printMessage("Build Failure", e.getMessage())
}

def buildSuccess(String target)
{
	printMessage("Build Success", target)
}
