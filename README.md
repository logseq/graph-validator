## Description

This is a [github action](https://github.com/features/actions) to run validation
tests on a Logseq graph. This action can also be run as a [CLI](#cli).
Validations check to ensure queries, block refs and properties are valid. This
action can catch errors that show up in the UI e.g. `Invalid query`.

## Usage

To setup this action, add the file `.github/workflows/test.yml` to your graph's
github repository with the following content:

``` yaml
on: [push]

jobs:
  test:
    runs-on: ubuntu-latest
    name: Run graph tests
    steps:
      - name: Checkout
        uses: actions/checkout@v3

      - name: Run graph-validator tests
        uses: logseq/graph-validator@main
```

That's it! This job will then run on future git pushes and fail if any invalid parts
of your graph are detected.

NOTE: The above example defaults to picking up new changes. If you'd prefer to stay on a stable version use the format `logseq/graph-validator@VERSION` e.g. `logseq/graph-validator@v0.1.0`. See CHANGELOG.md for released versions.

### Action Inputs

This action can take inputs e.g.:

```yaml
- name: Run graph-validator tests
  uses: logseq/graph-validator@main
  with:
    directory: logseq-graph-directory
    exclude: some-validation-test
```

This action has the following inputs:

#### `directory`

**Required:** The directory of the graph to test. Defaults to `.`.

#### `exclude`

Optional: A whitespace separated list of validations to exclude from running. Validation names are `deftest`s in `action.cljs` e.g. `tags-and-page-refs-have-pages`. Defaults to empty.

### CLI

To use this as a CLI locally, first install it:

```sh
$ git clone https://github.com/logseq/graph-validator
$ cd graph-validator && yarn install
$ yarn global add $PWD
```

Then use it from any logseq graph directory!
```sh
$ logseq-graph-validator
Parsing graph .
...
Ran 6 tests containing 9 assertions.
0 failures, 0 errors.

# Use the exclude option to exclude certain validations from being run
$ logseq-graph-validator --exclude assets-exist-and-are-used tags-and-page-refs-have-pages
Excluded test #'action/assets-exist-and-are-used
Excluded test #'action/tags-and-page-refs-have-pages
Parsing graph .
...
Ran 4 tests containing 5 assertions.
0 failures, 0 errors.
```

## Configuration

To configure the validator, create a `.graph-validator/config.edn` file in your
graph's directory. See [the config
file](https://github.com/logseq/graph-validator/blob/main/src/logseq/graph_validator/config.cljs)
for the full list of configuration keys.

## Custom Validations

Custom validations can be added to your graph by writing nbb-logseq compatible
[cljs tests](https://clojurescript.org/tools/testing) under `.graph-validator/`.
graph-validator already handles parsing the graph, so all a test does is
query against the graph's datascript db, `logseq.graph-parser.state/db-conn` See
`logseq.graph-parser.state` for other available state to use in tests. For
example, add a `.graph-validator/foo.cljs` with the content:

```cljs
(ns foo
  (:require [cljs.test :refer [deftest is]]
            [logseq.graph-validator.state :as state]
            [datascript.core :as d]))

(deftest no-page-named-foo
  (is (= 0
         (->> (d/q '[:find (pull ?b [*])
                     :in $ ?name
                     :where
                     [?b :block/name ?bn]
                     [(= ?name ?bn)]]
                   @state/db-conn
                   "foo")
              count))))
```

This test does a silly check that the page 'foo' doesn't exist in the graph. To
enable this custom test in your action, create `.graph-validator/config.edn`
with `{:add-namespaces [foo]}`.

For a real world example of a custom validation, see [this example in docs](https://github.com/logseq/docs/blob/master/.graph-validator/schema.cljs).

## Development

This github action use [nbb-logseq](https://github.com/logseq/nbb-logseq) and the [graph-parser
library](https://github.com/logseq/logseq/tree/master/deps/graph-parser) to analyze a Logseq graph
using its database and markdown AST data.

## Write your own Logseq action

This github action serves as an example that can be easily customized. This
action can validate almost anything in a Logseq graph as it has access to the
graph's database connection and to the full markdown AST of a graph. To write
your own action:

1. Copy this whole repository.
2. Write your own implementation in `action.cljs`.
   1. `logseq.graph-parser.cli/parse-graph` is the fn you'll want to create a database connection and fetch markdown ast data.
   2. This example uses `cljs.test` tests to run multiple validations on a graph. This is a personal preference and ultimately you only need your script to exit `0` on success and a non-zero code on failure.
3. Update `action.yml` with your action's name, description and inputs.

Your action can then be used as `user/repo@main`. To allow others to use specific versions of your action, [publish it](https://docs.github.com/en/actions/creating-actions/publishing-actions-in-github-marketplace).

### Github action type

This action [is a composite action](https://docs.github.com/en/actions/creating-actions/creating-a-composite-action) that installs dependencies at job runtime. It would have been preferable to use a [javascript action](https://docs.github.com/en/actions/creating-actions/creating-a-javascript-action) that already bundles dependencies with a tool like `ncc`. `ncc` is not able to handle dynamic imports i.e. requires of npm libraries in cljs code. https://github.com/borkdude/nbb-action-example demonstrates a workaround for this. Unfortunately the graph-parser library is a large, fast-moving library that would be difficult to maintain with such an approach. A docker action approach has not been investigated and could also be promising.

### Run this action elsewhere

You may want to run this locally or in another environment e.g. gitlab. To run this locally:

```sh
# Setup once
$ yarn install

# Run this each time
$ node graph_validator.mjs /path/to/graph
```

To run this in another environment, clone this repo, install dependencies and
run tests. These steps are shown in the `action.yml` file. You can ignore the
caching steps which are specific to github.

## LICENSE
See LICENSE.md

## Additional Links
* https://github.com/borkdude/nbb-action-example - Example nbb github action that inspired this one
* https://github.com/logseq/docs - Logseq graph that uses this action
