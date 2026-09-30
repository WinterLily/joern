int size(String value) => value.length;
String relay(String value) {
  size(value);
  return value;
}

String produce(String value) => value.trim();
String caller(String value) => relay(produce(value));
String separate(String input) {
  caller(input);
  return caller('fixed');
}
