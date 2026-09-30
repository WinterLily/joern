final trace = <String>[];

class Render {
  final int id;
  Render(this.id);

  @override
  String toString() {
    trace.add('string:$id');
    if (id < 0) throw StateError('conversion');
    return '$id';
  }
}

Render value(int id) {
  trace.add('value:$id');
  if (id == 0) throw StateError('expression');
  return Render(id);
}

String interpolate() => '${value(1)}:${value(2)}';
String adjacent() => '${value(1)}' ':' '${value(2)}';
String nested() => '${'${value(1)}'}:${value(2)}';
String expressionFailure() => '${value(1)}:${value(0)}';
String conversionFailure() => '${value(-1)}:${value(2)}';
String nullable(Render? input, String? text) => '$input:$text';
String identity(String input) => input;
String boundary() => '${identity('${value(1)}')}:${value(2)}';
