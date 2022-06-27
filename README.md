## Description

This is a [github action](https://github.com/features/actions) to run validation
tests on a Logseq graph. Currently it tests to ensure queries and block refs are
valid. This action can catch errors that show up in the UI e.g.
`Invalid query`.

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

### Action Inputs

This action can take inputs e.g.:

```yaml
- name: Run graph-validator tests
  uses: logseq/graph-validator@main
  with:
    directory: logseq-graph-directory
```

This action has the following inputs:

#### `directory`

**Required:** The directory of the graph to test. Defaults to `.`.

## Development

This github action use [nbb-logseq](https://github.com/logseq/nbb-logseq) and the [graph-parser library](https://github.com/logseq/logseq/tree/master/deps/graph-parser) to analyze a Logseq graph using its database and markdown AST data. This action is configured to run with yarn 3.X since the graph-parser library is depended on as a yarn workspace.

## Write your own Logseq action

This github action serves as an example that can be easily customized to
validate anything in a Logseq graph. To write your own action:

1. Copy this whole repository.
2. Write your own implementation in `action.cljs`.
   1. `logseq.graph-parser.cli/parse-graph` is the fn you'll want to create a database connection and fetch markdown ast data.
   2. This example uses `cljs.test` tests to run multiple validations on a graph. This is a personal preference and ultimately you only need your script to exit `0` on success and a non-zero code on failure.
3. Update `action.yml` with your action's name, description and inputs.

Your action can then be used as `user/repo@main`. To allow others to use specific versions of your action, [publish it](https://docs.github.com/en/actions/creating-actions/publishing-actions-in-github-marketplace).

### Github action type

This action [is a composite action](https://docs.github.com/en/actions/creating-actions/creating-a-composite-action) that installs dependencies at job runtime. It would have been preferable to use a [javascript action](https://docs.github.com/en/actions/creating-actions/creating-a-javascript-action) that already bundles dependencies with a tool like `ncc`. `ncc` is not able to handle dynamic imports i.e. requires of npm libraries in cljs code. https://github.com/borkdude/nbb-action-example demonstrates a workaround for this. Unfortunately the graph-parser library is a large, fast-moving library that would be difficult to maintain with such an approach. A docker action approach has not been investigated and could also be promising.

## LICENSE
See LICENSE.md

## Additional Links
* https://github.com/borkdude/nbb-action-example - Example nbb github action that inspired this one
* https://github.com/logseq/docs - Logseq graph that uses this action
