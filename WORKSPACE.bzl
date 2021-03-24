load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository") # buildifier: disable=load
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")  # buildifier: disable=load
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "maybe")  # buildifier: disable=load

load("@obazl_rules_ocaml//ocaml:providers.bzl", "OpamConfig", "BuildConfig")

################################################################
opam_pkgs = {
    # pin versions:
    # "base": ["v0.12.0"],
    "ocaml-compiler-libs": ["v0.11.0"],
    "compiler-libs.common": [],
    "zarith": ["1.7"],  # WARNING: depends on libgmp-dev

}

opam = OpamConfig(
    version = "2.0",
    builds  = {
        "coq-8.13.1": BuildConfig(
            default  = True,
            switch   = "coq-8.13.1",
            compiler = "4.11.1",
            packages = opam_pkgs,
            tools    = [
                "cppo",
                "ocaml-crunch"
            ]
        ),
        "4.11.1": BuildConfig(
            compiler = "4.11.1",
            packages = opam_pkgs
        ),
    }
)

#####################
def cc_fetch_rules():
    ## Bazel is migrating to this lib instead of builtin rules_cc.
    ## Use the http_archive rule once it is released.
    maybe(
        git_repository,
        name = "rules_cc",
        remote = "https://github.com/bazelbuild/rules_cc",
        commit = "b1c40e1de81913a3c40e5948f78719c28152486d",
        shallow_since = "1605101351 -0800"
        # branch = "master"
    )

    maybe(
        http_archive,
        name = "rules_foreign_cc",
        strip_prefix="rules_foreign_cc-main",
        url = "https://github.com/obazl/rules_foreign_cc/archive/main.zip"
        # sha256 = "3e6b0691fc57db8217d535393dcc2cf7c1d39fc87e9adb6e7d7bab1483915110"
    )
