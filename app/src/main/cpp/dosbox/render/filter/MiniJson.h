#pragma once

// A small JSON reader for GOA's filter bundles (assets/filters/<id>/filter.json, written by build-filters.py).
// Objects, arrays, strings (with \" \\ \/ \b \f \n \r \t \uXXXX escapes, ASCII only for \u), numbers, true/false/null.

#include <cstdlib>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace minijson {

struct Value {
    enum class Type { Null, Bool, Number, String, Array, Object } type{Type::Null};
    bool boolean{false};
    double number{0};
    std::string string;
    std::vector<Value> array;
    std::map<std::string, Value> object;

    bool isNull() const { return type == Type::Null; }
    const Value& operator[](const std::string& key) const {
        static const Value null;
        auto it = object.find(key);
        return it == object.end() ? null : it->second;
    }
    std::string str(const std::string& fallback = "") const { return type == Type::String ? string : fallback; }
    double num(double fallback = 0) const { return type == Type::Number ? number : fallback; }
    bool flag(bool fallback = false) const { return type == Type::Bool ? boolean : fallback; }
};

class Parser {
public:
    explicit Parser(const std::string& text) : s_(text) {}

    // Returns false (and an error message) on malformed input
    bool parse(Value& out, std::string& error) {
        try {
            ws();
            out = value();
            ws();
            if (i_ != s_.size()) fail("trailing characters");
            return true;
        } catch (const std::string& e) {
            error = e;
            return false;
        }
    }

private:
    const std::string& s_;
    size_t i_{0};

    [[noreturn]] void fail(const char* what) { throw std::string(what) + " at " + std::to_string(i_); }
    void ws() { while (i_ < s_.size() && (s_[i_] == ' ' || s_[i_] == '\n' || s_[i_] == '\r' || s_[i_] == '\t')) ++i_; }
    char peek() { if (i_ >= s_.size()) fail("unexpected end"); return s_[i_]; }
    void expect(char c) { if (peek() != c) fail("unexpected character"); ++i_; }
    bool lit(const char* word) {
        size_t n = std::char_traits<char>::length(word);
        if (s_.compare(i_, n, word) == 0) { i_ += n; return true; }
        return false;
    }

    Value value() {
        Value v;
        char c = peek();
        if (c == '{') {
            v.type = Value::Type::Object;
            ++i_; ws();
            if (peek() == '}') { ++i_; return v; }
            while (true) {
                ws();
                std::string key = string();
                ws(); expect(':'); ws();
                v.object[key] = value();
                ws();
                if (peek() == ',') { ++i_; continue; }
                expect('}');
                return v;
            }
        }
        if (c == '[') {
            v.type = Value::Type::Array;
            ++i_; ws();
            if (peek() == ']') { ++i_; return v; }
            while (true) {
                ws();
                v.array.push_back(value());
                ws();
                if (peek() == ',') { ++i_; continue; }
                expect(']');
                return v;
            }
        }
        if (c == '"') { v.type = Value::Type::String; v.string = string(); return v; }
        if (lit("true")) { v.type = Value::Type::Bool; v.boolean = true; return v; }
        if (lit("false")) { v.type = Value::Type::Bool; v.boolean = false; return v; }
        if (lit("null")) return v;
        const char* start = s_.c_str() + i_;
        char* end = nullptr;
        double d = std::strtod(start, &end);
        if (end == start) fail("bad value");
        i_ += end - start;
        v.type = Value::Type::Number;
        v.number = d;
        return v;
    }

    std::string string() {
        expect('"');
        std::string out;
        while (true) {
            char c = peek(); ++i_;
            if (c == '"') return out;
            if (c != '\\') { out += c; continue; }
            char e = peek(); ++i_;
            switch (e) {
                case '"': out += '"'; break;
                case '\\': out += '\\'; break;
                case '/': out += '/'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'n': out += '\n'; break;
                case 'r': out += '\r'; break;
                case 't': out += '\t'; break;
                case 'u': {
                    if (i_ + 4 > s_.size()) fail("bad \\u escape");
                    unsigned code = std::strtoul(s_.substr(i_, 4).c_str(), nullptr, 16);
                    i_ += 4;
                    // Bundle text is ASCII except names and credits, which the renderer never needs
                    out += code < 0x80 ? (char)code : '?';
                    break;
                }
                default: fail("bad escape");
            }
        }
    }
};

}  // namespace minijson
