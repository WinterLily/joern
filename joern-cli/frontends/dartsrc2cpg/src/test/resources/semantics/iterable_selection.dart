String selected(String input) =>
    ['fixed'].where((value) => input.isNotEmpty).join();
String elements(String input) => [input].where((value) => true).join();
String unrelated(String input) => ['fixed'].where((value) => true).join();
String mapped(String input) => [input].map((value) => value).join();
String constantMapped(String input) => [input].map((value) => 'fixed').join();
