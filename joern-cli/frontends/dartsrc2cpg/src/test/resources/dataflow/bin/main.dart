import '../lib/helper.dart';

void sink(String value) {}

void main(List<String> args) {
  final input = args[0];
  sink(relay(input));
}
