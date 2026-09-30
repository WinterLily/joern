String selected(String input) =>
    ['fixed'].where((value) => input.isNotEmpty).join();
String elements(String input) => [input].where((value) => true).join();
String unrelated(String input) => ['fixed'].where((value) => true).join();
