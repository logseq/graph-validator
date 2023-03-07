#!/usr/bin/env node

import { loadFile, addClassPath } from '@logseq/nbb-logseq'
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';

const __dirname = fileURLToPath(dirname(import.meta.url));
const { main } = await loadFile(resolve(__dirname, 'action.cljs'));

// Expects to be called as node X.js ...
const args = process.argv.slice(2)
// Add classpath for user namespaces
addClassPath(resolve(args[0] || '.', '.graph-validator'));
main.apply(null, args);
