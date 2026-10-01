final trace = <String>[];

String relay(String value) {
  trace.add('relay');
  return value;
}

String longer(String value) {
  trace.add('longer');
  return relay(value);
}

String choice(String input, bool first, bool second) {
  final value = first
      ? input
      : second
      ? relay(input)
      : longer(input);
  return value;
}

String repeated(String input, bool first, bool second) {
  choice('constant', first, second);
  return choice(input, first, second);
}

String independent(String input, bool first, bool second) {
  choice(input, first, second);
  return choice('constant', first, second);
}
