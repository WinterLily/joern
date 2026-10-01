abstract class Choice {
  String pick({String first = 'abstract', String ignored = 'unused'});
}

class Ordered implements Choice {
  @override
  String pick({
    String ignored = 'ordered ignored',
    String first = 'ordered',
    String extra = 'ordered extra',
  }) => first;
}

class ExtraFirst implements Choice {
  @override
  String pick({
    String extra = 'extra first extra',
    String first = 'extra first',
    String ignored = 'extra first ignored',
  }) => first;
}

class ExtraResult implements Choice {
  @override
  String pick({
    String first = 'extra result unused',
    String ignored = 'extra result ignored',
    String extra = 'additional result',
  }) => extra;
}

String literalSupplied(Choice receiver) => receiver.pick(first: 'abstract');
String omitted(Choice receiver) => receiver.pick();
String boundOmitted(Choice receiver) {
  final callback = receiver.pick;
  return callback();
}

String explicit(Choice receiver, String input, String ignored) =>
    receiver.pick(ignored: ignored, first: input);
String bound(Choice receiver, String input, String ignored) {
  final callback = receiver.pick;
  return callback(ignored: ignored, first: input);
}

class Cell {
  String value;
  Cell(this.value);
}

abstract class Writer {
  void write({
    required Cell target,
    required Cell other,
    required String input,
  });
}

class ReorderedWriter implements Writer {
  @override
  void write({
    required Cell other,
    required String input,
    required Cell target,
    bool optional = false,
  }) {
    target.value = input;
  }
}

String written(Writer receiver, String input) {
  final target = Cell('constant');
  final other = Cell('other');
  receiver.write(target: target, other: other, input: input);
  return target.value;
}

String unrelated(Writer receiver, String input) {
  final target = Cell('constant');
  final other = Cell('other');
  receiver.write(target: target, other: other, input: input);
  return other.value;
}

class BaseDefault {
  String pick({String first = 'base'}) => first;
}

class PrefixDefault extends BaseDefault {
  @override
  String pick({String first = 'prefix'}) => first;
}

mixin DefaultMixin on BaseDefault {
  String mixed() => super.pick();
}

class DirectDefault extends BaseDefault with DefaultMixin {}

class AppliedDefault extends PrefixDefault with DefaultMixin {}
