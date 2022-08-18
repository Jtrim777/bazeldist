load("@rules_python//python:pip.bzl", "pip_install")

def deps():
    pip_install(
        name = "bazeldist_pip",
        requirements = "@bazeldist//pip:requirements.txt",
    )
