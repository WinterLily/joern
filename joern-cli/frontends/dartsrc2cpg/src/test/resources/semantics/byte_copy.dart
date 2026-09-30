import 'dart:typed_data';

Uint8List copy(Uint8List input) {
  final output = Uint8List(input.length);
  output.setRange(0, input.length, input);
  return output;
}

Uint8List allocate(Uint8List input) => Uint8List(input.length);
Uint8List separate(Uint8List input) {
  final output = Uint8List(input.length);
  final unrelated = Uint8List(input.length);
  unrelated.setRange(0, input.length, input);
  return output;
}

void main() {}

Uint8List sourceIsolation(Uint8List input) {
  final source = Uint8List(2);
  input.setRange(0, input.length, source);
  return source;
}
