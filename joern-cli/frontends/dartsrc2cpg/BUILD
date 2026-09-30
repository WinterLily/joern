load("//bazel:java.bzl", "java_binary")
load("//bazel:scala.bzl", "scala_library", "scala_test")

scala_library(
    name = "dartsrc2cpg",
    srcs = glob(["src/main/scala/**/*.scala"]),
    visibility = ["//visibility:public"],
    deps = [
        "//bazel/reexports:io_shiftleft_codepropertygraph_with_deps",
        "//joern-cli/frontends/x2cpg",
        "//semanticcpg",
        "@maven//:com_github_scopt_scopt_3",
        "@maven//:com_lihaoyi_ujson_3",
        "@maven//:org_slf4j_slf4j_api",
    ],
)

java_binary(
    name = "dartsrc2cpg-bin",
    main_class = "io.joern.dartsrc2cpg.Main",
    visibility = ["//visibility:public"],
    runtime_deps = [
        ":dartsrc2cpg",
        "@maven//:org_apache_logging_log4j_log4j_slf4j2_impl",
    ],
)

scala_test(
    name = "tests",
    srcs = glob(["src/test/scala/**/*.scala"]),
    data = glob(["src/test/resources/**/*"]),
    env = {"LC_ALL": "en_US.UTF-8"},
    env_inherit = ["DART_SDK", "DART_ASTGEN", "DART_TEST_REPOSITORY"],
    tags = ["local"],
    deps = [
        ":dartsrc2cpg",
        "//bazel/reexports:io_shiftleft_codepropertygraph_with_deps",
        "//dataflowengineoss",
        "//joern-cli/frontends/x2cpg",
        "//semanticcpg",
        "@maven//:com_lihaoyi_ujson_3",
    ],
)
