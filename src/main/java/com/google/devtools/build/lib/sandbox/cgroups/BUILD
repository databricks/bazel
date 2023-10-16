load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "cgroups",
    srcs = glob([
        "*.java",
        "v1/*.java",
        "v2/*.java",
    ]),
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:exec_exception",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:auto_value",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)
