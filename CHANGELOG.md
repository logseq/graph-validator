## Unreleased
* Fix `assets-exist-and-are-used` validation - asset subdirectories caused a false negative
* Fix `tags-and-page-refs-have-pages` validation - whiteboard page refs weren't recognized

## 0.4.0
* Add support for custom validations
* Introduce a .graph-validator/config.edn
* Introduce a CLI version of the action

## 0.3.0
* Add validation for tags and page-refs having pages
* Add validation for assets existing and being used
* Add exclude option for excluding certain validations

## 0.2.0
* Add detection of invalid properties
* Internally action uses nbb.edn instead of yarn workspaces for managing logseq dep. Much easier to
  manage graph-parser updates
* Bump to logseq/graph-parser 223b62de28855d5ac6d82a330aa3c16ec1165272 (0.8.10)

## 0.1.0

* Initial release with detection of invalid block refs and invalid queries
