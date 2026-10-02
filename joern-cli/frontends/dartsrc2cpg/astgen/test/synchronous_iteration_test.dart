import 'package:test/test.dart';

import '../../src/test/resources/semantics/synchronous_iteration.dart'
    as fixture;

void main() {
  setUp(fixture.trace.clear);

  test('synchronous loops evaluate members once in iteration order', () {
    expect(fixture.declared(), ['first', 'second']);
    expect(fixture.trace, [
      'source',
      'iterator',
      'moveNext',
      'current',
      'body:first',
      'moveNext',
      'current',
      'body:second',
      'moveNext',
    ]);
    fixture.trace.clear();
    expect(fixture.assigned(fixture.Values(['first', 'second'])), [
      'first',
      'second',
    ]);
    expect(fixture.trace, [
      'iterator',
      'moveNext',
      'current',
      'body:first',
      'moveNext',
      'current',
      'body:second',
      'moveNext',
    ]);
  });

  test('collection loops use generic and interface iteration members', () {
    for (final collect in [fixture.collected, fixture.throughInterface]) {
      fixture.trace.clear();
      expect(collect(fixture.Values(['first', 'second'])), ['first', 'second']);
      expect(fixture.trace, [
        'iterator',
        'moveNext',
        'current',
        'moveNext',
        'current',
        'moveNext',
      ]);
    }
    fixture.trace.clear();
    expect(fixture.patterned(fixture.Values([('left', 'right')])), [
      'left:right',
    ]);
    expect(fixture.trace, ['iterator', 'moveNext', 'current', 'moveNext']);
    fixture.trace.clear();
    expect(fixture.collected(fixture.Values<String>([])), isEmpty);
    expect(fixture.trace, ['iterator', 'moveNext']);
  });
}
