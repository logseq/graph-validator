## Description

This is a [github action](https://github.com/features/actions) to run tests on a logseq graph. Currently it tests to ensure queries and block refs are valid.

## Inputs

## `directory`

**Required** The directory of the graph to test. Defaults to `.`.

## Usage

Create the file `.github/workflows/test.yml` in your graph's github repository
with the following content:

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

## Development
TODO

## LICENSE
See LICENSE.md

## Additional Links
TODO
