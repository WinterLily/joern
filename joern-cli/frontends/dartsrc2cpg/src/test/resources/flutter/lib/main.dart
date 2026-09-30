import 'package:flutter/material.dart';

void sink(String value) {}

Widget buildButton(String input) =>
    TextButton(onPressed: () => sink(input), child: Text(input));

Widget buildConstantButton(String input) => TextButton(
  onPressed: () => sink('constant'),
  child: const Text('constant'),
);

void main() => runApp(MaterialApp(home: buildButton('hello')));
