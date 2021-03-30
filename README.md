# Coq

This is a fork of Coq for adding Bazel build support. See the
[upstream repo](https://github.com/coq/coq) or
[README.upstream.md](README.upstream.md) for the authoritative README file.

>    The Bazel rules are documented at
[OBazl](https://obazl.github.io/docs_obazl/). **CAVEAT**: OBazl is new;
it's fairly stable but is still undergoing development. The docs may
be a little outdated.

This build uses the `dev` branch of `rules_obazl`; see the
`git_repository` rule in [WORKSPACE.bazel](WORKSPACE.bazel). That
branch is unstable, so at any given time the build may fail. If this
happens, get in touch on the [OBazl Discord
server](https://discord.gg/PhVnhnSZqT).

You can control the version of OBazl used. Clone the OBazl repo to
your local system and checkout the commit or branch you want. Then
create file `user.bazelrc` in the Coq project root directory and add a
line like the following:

```
common --override_repository=obazl_rules_ocaml=/ABSOLUTE/PATH/TO/rules_ocaml
```

If you're on a Mac and you get annoying messages about versions you
can add something like the following to `user.bazelrc`:

```
build:macos --macos_minimum_os='10.15'
build --config=macos
```

The default configuration (in [WORKSPACE.bzl](WORKSPACE.bzl)) uses
OCaml version 4.11.1. You must install this using OPAM, and you must
install the OPAM libs listed in the `opam_pkgs` dict that file.

## Build Targets

Currently only the OCaml parts of Coq can be built with Bazel; support
for building the `.v` files etc. is under development.

In general each subdirectory with OCaml sources contains a build
 target whose name matches the directory name; this target (usually)
 builds an archive. For example:

```
$ bazel build clib
```

But this is not always the case; for example, package
`//plugins/syntax` does not contain a target
`//plugins/syntax:syntax`. To list all the target rules in a package:

```
$ bazel query 'kind(rule, //plugins/syntax:*)' --output label_kind
```

>    To enable verbose builds, pass `--config=dbg` on the command line. To
make this the default, add it to `user.bazelrc`.

You can also build everything in a (Bazel) package by globbing:

```
$ bazel build clib/...:*
```

You can also build any particular target:

```
$ bazel build vernac:_ComFixpoint
```

You can use Bazel's [query
facility](https://docs.bazel.build/versions/master/query-how-to.html)
to inspect dependency structures. For example, to list all
dependencies of `//vernac:_ComFixpoint`:

```
$ bazel query 'deps(vernac:_ComFixpoint)' --notool_deps --output graph
```

To print the dependency paths from `//vernac:_ComFixPoint` to
`//kernel:_Names`:

```
$ bazel query 'allpaths(vernac:_ComFixpoint, kernel:_Names)' --notool_deps --output graph
```

Which targets depend on `//vernac:_ComFixpoint`?

```
$ bazel query 'rdeps(..., //vernac:_ComFixpoint)'
```

Restrict the output to show only packages:

```
$ bazel query 'rdeps(..., //vernac:_ComFixpoint)' --output package
```

You can also ask Bazel to print the build command for a target without
actually executing the build by using `aquery`, e.g.

```
$ bazel aquery plugins/rtauto
```
