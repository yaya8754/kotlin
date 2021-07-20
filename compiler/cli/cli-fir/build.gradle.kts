plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compile(project(":compiler:util"))
    compile(project(":compiler:cli-common"))
    compile(project(":compiler:cli"))
    compile(project(":compiler:cli-js"))
    compile(project(":compiler:frontend"))
    compile(project(":compiler:backend-common"))
    compile(project(":compiler:ir.backend.common"))
    compile(project(":compiler:frontend"))
    compile(project(":compiler:backend.js"))
    compile(project(":js:js.translator"))
    compile(project(":js:js.serializer"))
    compile(project(":js:js.dce"))
    compile(project(":compiler:incremental-compilation-impl"))
    implementation(project(":compiler:fir:entrypoint"))
    implementation(project(":compiler:backend.jvm"))
    implementation(project(":compiler:backend.jvm:backend.jvm.entrypoint"))
    implementation(project(":compiler:ir.serialization.common"))
    implementation(project(":compiler:ir.serialization.jvm"))
    implementation(project(":compiler:ir.tree.impl"))

    compileOnly(intellijCoreDep()) { includeJars("intellij-core") }

    testCompile(project(":kotlin-test:kotlin-test-jvm"))
    testCompile(commonDep("junit:junit"))
    testCompileOnly(project(":kotlin-test:kotlin-test-jvm"))
    testCompileOnly(project(":kotlin-test:kotlin-test-junit"))
    testCompile(projectTests(":compiler:tests-common"))
    testRuntime(intellijCoreDep()) { includeJars("intellij-core") }
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

projectTest(parallel = true) {
    dependsOn(":dist")
    workingDir = rootDir
}