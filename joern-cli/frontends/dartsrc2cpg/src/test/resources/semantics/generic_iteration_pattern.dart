import 'synchronous_iteration.dart';

List<String> boundedPattern<T extends Values<(String, String)>>(T values) => [
  for (final (first, second) in values) '$first:$second',
];

void main() {}
