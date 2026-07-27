import js from '@eslint/js'
import globals from 'globals'
import pluginVue from 'eslint-plugin-vue'
import vueParser from 'vue-eslint-parser'
import tseslint from 'typescript-eslint'

const uniGlobals = { uni: 'readonly', wx: 'readonly', plus: 'readonly', getApp: 'readonly', getCurrentPages: 'readonly', UniApp: 'readonly', UniNamespace: 'readonly', AnyObject: 'readonly' }

export default tseslint.config(
  { ignores: ['dist/**', 'unpackage/**', 'node_modules/**', 'src/static/**', 'scripts/**', '.test-output/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  { languageOptions: { ecmaVersion: 'latest', sourceType: 'module', globals: { ...globals.browser, ...globals.es2021, ...uniGlobals } } },
  { files: ['**/*.vue'], languageOptions: { parser: vueParser, parserOptions: { parser: tseslint.parser, ecmaVersion: 'latest', sourceType: 'module', extraFileExtensions: ['.vue'] } } },
  { rules: {
    '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' }],
    '@typescript-eslint/no-explicit-any': 'warn', 'vue/multi-word-component-names': 'off',
    'vue/max-attributes-per-line': 'off', 'vue/singleline-html-element-content-newline': 'off', 'vue/multiline-html-element-content-newline': 'off', 'vue/html-self-closing': 'off', 'vue/html-indent': 'off', 'vue/html-closing-bracket-newline': 'off', 'vue/html-closing-bracket-spacing': 'off', 'vue/mustache-interpolation-spacing': 'off', 'vue/attributes-order': 'off',
    '@typescript-eslint/no-empty-object-type': ['error', { allowInterfaces: 'with-single-extends' }],
    'no-console': ['warn', { allow: ['warn', 'error'] }], eqeqeq: ['error', 'smart'], 'no-var': 'error', 'prefer-const': 'error',
  } },
)
