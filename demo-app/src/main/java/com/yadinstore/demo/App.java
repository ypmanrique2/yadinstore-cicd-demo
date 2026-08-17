package com.yadinstore.demo;

/**
 * App minima de demostracion del pipeline CI/CD (Jenkins + GitHub Actions).
 * No requiere secrets: el pipeline compila, testea y empaqueta.
 */
public final class App {

    private App() {
    }

    public static String greeting(String name) {
        if (name == null || name.isBlank()) {
            return "Hello, CI/CD!";
        }
        return "Hello, " + name + "!";
    }

    public static void main(String[] args) {
        System.out.println(greeting(args.length > 0 ? args[0] : "CI/CD"));
    }
}