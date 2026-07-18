import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import tseslint from "typescript-eslint";

export default tseslint.config(
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["frontend/chat/src/**/*.{ts,tsx}"],
    languageOptions: {
      ecmaVersion: 2021,
      globals: globals.browser,
    },
    plugins: {
      "react-hooks": reactHooks,
      "react-refresh": reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      "react-refresh/only-export-components": "off",
      "react-hooks/set-state-in-effect": "off",
      "@typescript-eslint/triple-slash-reference": "off",
      "@typescript-eslint/no-explicit-any": "error",
    },
  },
  {
    files: ["frontend/chat/src/components/ai-elements/**/*.{ts,tsx}"],
    rules: {
      "react-hooks/refs": "off",
      "react-hooks/static-components": "off",
      "@typescript-eslint/no-unused-vars": "off",
    },
  },
);
