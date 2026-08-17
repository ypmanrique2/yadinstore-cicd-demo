package com.yadinstore.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppTest {

    @Test
    void greetingDefault() {
        assertEquals("Hello, CI/CD!", App.greeting(null));
        assertEquals("Hello, CI/CD!", App.greeting("  "));
    }

    @Test
    void greetingWithName() {
        assertEquals("Hello, Jenkins!", App.greeting("Jenkins"));
        assertEquals("Hello, GitHub Actions!", App.greeting("GitHub Actions"));
    }
}