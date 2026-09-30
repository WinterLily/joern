import 'interpolation_order.dart' as fixture;

void main() {
  const javascript = bool.fromEnvironment('dart.library.js_interop');
  void expectTrace(List<String> expected) {
    if (fixture.trace.join(',') != expected.join(',')) {
      throw StateError('Expected $expected, observed ${fixture.trace}');
    }
  }

  for (final run in [fixture.interpolate, fixture.adjacent, fixture.nested]) {
    fixture.trace.clear();
    if (run() != '1:2') throw StateError('Incorrect interpolation value');
    expectTrace(
      javascript
          ? ['value:1', 'string:1', 'value:2', 'string:2']
          : ['value:1', 'value:2', 'string:1', 'string:2'],
    );
  }
  fixture.trace.clear();
  if (fixture.boundary() != '1:2') throw StateError('Incorrect nested value');
  expectTrace(['value:1', 'string:1', 'value:2', 'string:2']);

  fixture.trace.clear();
  var failed = false;
  try {
    fixture.expressionFailure();
  } on StateError {
    failed = true;
  }
  if (!failed) throw StateError('Missing expression failure');
  expectTrace(
    javascript ? ['value:1', 'string:1', 'value:0'] : ['value:1', 'value:0'],
  );

  fixture.trace.clear();
  failed = false;
  try {
    fixture.conversionFailure();
  } on StateError {
    failed = true;
  }
  if (!failed) throw StateError('Missing conversion failure');
  expectTrace(
    javascript
        ? ['value:-1', 'string:-1']
        : ['value:-1', 'value:2', 'string:-1'],
  );

  fixture.trace.clear();
  if (fixture.nullable(null, null) != 'null:null') {
    throw StateError('Incorrect null interpolation');
  }
  expectTrace([]);
  print('Interpolation ${javascript ? 'dart2js' : 'VM'} oracle passed');
}
