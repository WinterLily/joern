import 'package:test/test.dart';

import '../../src/test/resources/semantics/list_pattern_defaults.dart'
    as patterns;

void main() {
  test('trailing rest patterns omit end rather than supplying null', () {
    for (final first in ['first', 'independent']) {
      final input = patterns.Values([first, 'second', 'third', 'fourth']);
      patterns.trace.clear();
      expect(patterns.allConcrete(input), [first, 'second']);
      expect(patterns.trace, ['slice:0:2']);
      patterns.trace.clear();
      expect(patterns.head(input), ['second']);
      expect(patterns.trace, ['length', 'slice:1:2']);
      patterns.trace.clear();
      expect(patterns.explicitNull(input), input.items);
      expect(patterns.trace, ['slice:0:null']);
    }
  });

  test('virtual rest calls use each implementation default', () {
    for (final first in ['first', 'independent']) {
      final items = [first, 'second', 'third', 'fourth'];
      patterns.trace.clear();
      expect(patterns.allInterface(patterns.Values(items)), [first, 'second']);
      expect(patterns.trace, ['slice:0:2']);
      patterns.trace.clear();
      expect(patterns.allInterface(patterns.OtherValues(items)), [first]);
      expect(patterns.trace, ['other:0:1']);
      patterns.trace.clear();
      expect(patterns.allInterface(items), items);
      expect(patterns.trace, isEmpty);
    }
  });

  test('rest patterns with tail elements supply the computed end', () {
    for (final first in ['first', 'independent']) {
      final items = [first, 'second', 'third', 'fourth'];
      for (final input in [
        patterns.Values(items),
        patterns.OtherValues(items),
      ]) {
        patterns.trace.clear();
        expect(patterns.tail(input), [first, 'second', 'third']);
        expect(patterns.trace, [
          'length',
          input is patterns.OtherValues ? 'other:0:3' : 'slice:0:3',
        ]);
      }
    }
  });

  test(
    'cached rest calls evaluate defaults once and wildcards skip extraction',
    () {
      for (final accept in [false, true]) {
        patterns.trace.clear();
        expect(
          patterns.cached(
            patterns.Values(['first', 'second', 'third']),
            accept,
          ),
          ['second'],
        );
        expect(patterns.trace, ['length', 'slice:1:2']);
        patterns.trace.clear();
        expect(
          patterns.cached(
            patterns.OtherValues(['first', 'second', 'third']),
            accept,
          ),
          isEmpty,
        );
        expect(patterns.trace, ['length', 'other:1:1']);
      }
      patterns.trace.clear();
      expect(patterns.wildcard(patterns.Values(['first'])), isTrue);
      expect(patterns.trace, isEmpty);
    },
  );

  test('omitted slice defaults preserve short-list failure', () {
    final input = patterns.Values(['only']);
    patterns.trace.clear();
    expect(() => patterns.allConcrete(input), throwsRangeError);
    expect(patterns.trace, ['slice:0:2']);
    patterns.trace.clear();
    expect(patterns.explicitNull(input), ['only']);
    expect(patterns.trace, ['slice:0:null']);
  });
}
