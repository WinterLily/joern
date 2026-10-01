import 'default_argument_implementations.dart';

abstract class NamedChoice {
  String pick({String first = 'declaration', String ignored = 'unused'});
}

abstract class PositionalChoice {
  String pick([String first = 'declaration']);
}

final events = <String>[];

NamedChoice namedReceiver() {
  events.add('receiver');
  return NamedOverride();
}

String argument(String label, String value) {
  events.add(label);
  return value;
}

String traced(String input) => namedReceiver().pick(
  ignored: argument('ignored', 'unused'),
  first: argument('first', input),
);

String tracedIgnored(String input) => namedReceiver().pick(
  ignored: argument('ignored', input),
  first: argument('first', 'constant'),
);

String tracedBound(String input) {
  final callback = namedReceiver().pick;
  callback(
    ignored: argument('ignored1', 'unused'),
    first: argument('first1', 'constant'),
  );
  return callback(
    ignored: argument('ignored2', 'unused'),
    first: argument('first2', input),
  );
}

String repeated(NamedChoice receiver, String input) {
  receiver.pick(first: input);
  return receiver.pick(first: 'constant');
}

String repeatedInput(NamedChoice receiver, String input) {
  receiver.pick(first: 'constant');
  return receiver.pick(first: input);
}

String differingMasks(NamedChoice receiver, String input) {
  receiver.pick(first: input);
  return receiver.pick();
}

String captured(NamedChoice first, NamedChoice second) {
  final left = first.pick;
  final right = second.pick;
  left();
  return right();
}

String positionalOmitted(PositionalChoice receiver) => receiver.pick();
String positionalSupplied(PositionalChoice receiver, String input) =>
    receiver.pick(input);
String positionalBound(PositionalChoice receiver) {
  final callback = receiver.pick;
  return callback();
}
