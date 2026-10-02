import 'dart:async';

import 'package:test/test.dart';

import '../../src/test/resources/semantics/async_iteration_members.dart'
    as fixture;

void main() {
  test(
    'SDK iteration retains typed, generic, wrapped and pattern values',
    () async {
      for (final values in [
        <String>[],
        ['first'],
        ['first', 'second'],
      ]) {
        expect(await fixture.typed(Stream.fromIterable(values)), values);
        expect(
          await fixture.symbolic<String>(Stream.fromIterable(values)),
          values,
        );
        expect(await fixture.bounded(Stream.fromIterable(values)), values);
        final wrapped = values.map(fixture.Value<String>.new).toList();
        expect(
          await fixture.wrapped(fixture.Values(Stream.fromIterable(wrapped))),
          wrapped,
        );
        expect(
          await fixture.dynamicSource(Stream.fromIterable(values)),
          values,
        );
      }
      expect(
        await fixture.patterned(
          Stream.fromIterable([('first', 1), ('second', 2)]),
        ),
        ['first:1', 'second:2'],
      );
      await expectLater(
        fixture.dynamicSource(['invalid']),
        throwsA(isA<TypeError>()),
      );
    },
  );

  test(
    'implicit and explicit SDK iteration keep sources and failures separate',
    () async {
      final failure = StateError('stream');
      final stack = StackTrace.current;
      Future<List<String>> explicit(Stream<String> stream) async {
        final iterator = StreamIterator(stream);
        final result = <String>[];
        try {
          while (await iterator.moveNext()) {
            result.add(iterator.current);
          }
        } finally {
          await iterator.cancel();
        }
        return result;
      }

      for (final collect in [fixture.typed, explicit]) {
        final trace = <String>[];
        Stream<String> source(String name, List<String> values) {
          trace.add('source:$name');
          return Stream.fromIterable(values).map((value) {
            trace.add('event:$name:$value');
            return value;
          });
        }

        expect(await collect(source('left', ['input'])), ['input']);
        expect(await collect(source('right', ['independent'])), [
          'independent',
        ]);
        expect(trace, [
          'source:left',
          'event:left:input',
          'source:right',
          'event:right:independent',
        ]);
        Object? caught;
        StackTrace? caughtStack;
        try {
          await collect(Stream<String>.error(failure, stack));
        } catch (error, trace) {
          caught = error;
          caughtStack = trace;
        }
        expect(caught, same(failure));
        expect(caughtStack, same(stack));
      }
    },
  );
}
