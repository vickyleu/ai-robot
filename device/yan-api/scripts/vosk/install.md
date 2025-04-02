### kaldi构建
### 编译器
export CC=armv7-unknown-linux-gnueabihf-gcc
export CXX=armv7-unknown-linux-gnueabihf-g++
export AR=armv7-unknown-linux-gnueabihf-ar
export AS=armv7-unknown-linux-gnueabihf-as
export LD=armv7-unknown-linux-gnueabihf-ld
export RANLIB=armv7-unknown-linux-gnueabihf-ranlib
export STRIP=armv7-unknown-linux-gnueabihf-strip
export NM=armv7-unknown-linux-gnueabihf-nm
export OBJCOPY=armv7-unknown-linux-gnueabihf-objcopy
export OBJDUMP=armv7-unknown-linux-gnueabihf-objdump
export READELF=armv7-unknown-linux-gnueabihf-readelf
export SYSROOT=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/armv7-unknown-linux-gnueabihf/sysroot
export PATH=$PATH:/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin
export CFLAGS="-march=armv7-a -mfpu=neon -mfloat-abi=hard -fPIC -I${SYSROOT}/usr/include"
export CXXFLAGS="${CFLAGS} -std=c++17 -I${SYSROOT}/../include/c++/13.3.0 -I${SYSROOT}/../include/c++/13.3.0/armv7-unknown-linux-gnueabihf"
export LDFLAGS="--sysroot=${SYSROOT} -L${SYSROOT}/usr/lib -L${SYSROOT}/../lib -L${SYSROOT}/../include/c++/13.3.0/armv7-unknown-linux-gnueabihf"
export OLD_LDFLAGS="${LDFLAGS}"
export LDFLAGS="${LDFLAGS} -static-libstdc++"
export CXXFLAGS="${CXXFLAGS} -I/Volumes/Extra/Github/ktor/library/zlib-1.3.1/build/include"
export LDFLAGS="${LDFLAGS} -L/Volumes/Extra/Github/ktor/library/zlib-1.3.1/build/lib"
export ICU_ROOT=/Volumes/Extra/Github/ktor/library/icu-release-77-1/build
export CXXFLAGS="${CXXFLAGS} -O3"
export CMAKE_OSX_ARCHITECTURES=""
export CMAKE_OSX_SYSROOT=""

# 在链接命令末尾添加 -static-libstdc++ 以确保使用静态库：这里执行还原
export LDFLAGS="${OLD_LDFLAGS}" #recovery



# 1. 下载vosk定制版kaldi
cd <KALDI_ROOT>
git clone -b vosk --single-branch --depth=1 https://github.com/alphacep/kaldi kaldi
cd kaldi
# 2. 配置kaldi工具链

cd kaldi/tools

# 交叉编译需要修改Makefile 增加--host=??
```makefile
openfst-$(OPENFST_VERSION)/Makefile: openfst-$(OPENFST_VERSION)
cd openfst-$(OPENFST_VERSION)/ && \
autoreconf -i && \
./configure --prefix=`pwd` --build=aarch64-apple-darwin23.3.0 --host=armv7-linux-gnueabihf $(OPENFST_CONFIGURE) CXX="$(CXX)" \
CXXFLAGS="$(openfst_add_CXXFLAGS) $(CXXFLAGS)  " \
LDFLAGS="$(LDFLAGS)" LIBS="-ldl"
还要去掉-msse -msse2, armv7不支持
  openfst_add_CXXFLAGS = -g -O3 #//-msse -msse2
  
OS := $(shell uname -s)
SCTK_CXFLAGS = -w
ifneq ($(OS), Darwin)
SCTK_CXFLAGS += -march=armv7-a
endif
```
# 同时还要修改extras/check_dependencies.sh
```bash
*"c++ "* | *"g++ "*  )
g++前面需要一个通配符,否则会判断错误
# Cannot check this without a compiler.
if have "$CXX" && ! echo "#include <zlib.h>" | $CXX $CXXFLAGS -E - &>/dev/null; then
  echo "$0: zlib is not installed."
  add_packages zlib-devel zlib  # zlib1g-dev mac下zlib不能用 
fi
```

```bash
# 在脚本中添加这些环境变量
export CMAKE_OSX_ARCHITECTURES=""
export CMAKE_OSX_SYSROOT=""
# 执行前记得把CLAPACK改一下名字_CLAPACK什么的,等下面的执行完, 然后把头文件复制到OpenBLAS的install目录include下面去, vosk写脚本的人没脑子

# 修改./extras/install_openblas_clapack.sh内容, 否则无法编译
OPENBLAS_VERSION=0.3.20
CLAPACK_VERSION=3.2.1
git clone -b v0.3.20 --single-branch https://github.com/xianyi/OpenBLAS
git clone -b v3.2.1 --single-branch https://github.com/alphacep/clapack
make -C OpenBLAS ONLY_CBLAS=1 CXXFLAGS="${CXXFLAGS}" CFLAGS="${CFLAGS}"  LDFLAGS="${LDFLAGS}" DYNAMIC_ARCH=0 CROSS=1 HOSTCC=gcc CC=armv7-unknown-linux-gnueabihf-gcc  TARGET=ARMV7    BINARY=32   USE_LOCKING=1 USE_THREAD=0 NUM_THREADS=512 all
make -C OpenBLAS PREFIX=$(pwd)/OpenBLAS/install install



git clone -b v${OPENBLAS_VERSION} --single-branch https://github.com/xianyi/OpenBLAS
git clone -b v${CLAPACK_VERSION} --single-branch https://github.com/alphacep/clapack \


make -C OpenBLAS PREFIX=$(pwd)/OpenBLAS/install install


mkdir -p clapack/BUILD && cd clapack/BUILD && cmake  ..  \
  -DCMAKE_SYSTEM_NAME=Linux \
  -DCMAKE_SYSTEM_PROCESSOR=armv7 \
  -DCMAKE_OSX_ARCHITECTURES="" \
  -DCMAKE_OSX_DEPLOYMENT_TARGET="" \
  -DCMAKE_C_COMPILER=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin/armv7-unknown-linux-gnueabihf-gcc \
  -DCMAKE_CXX_COMPILER=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin/armv7-unknown-linux-gnueabihf-g++ \
  -DCMAKE_C_FLAGS="${CFLAGS}" \
  -DCMAKE_CXX_FLAGS="${CXXFLAGS}" \
  -DCMAKE_EXE_LINKER_FLAGS="${LDFLAGS}" 

mkdir -p clapack/BUILD && cd clapack/BUILD && cmake  ..  \
  -DCMAKE_SYSTEM_NAME=Linux \
  -DCMAKE_SYSTEM_PROCESSOR=armv7 \
  -DCMAKE_OSX_ARCHITECTURES="" \
  -DCMAKE_OSX_DEPLOYMENT_TARGET="" \
  -DCMAKE_C_COMPILER=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin/armv7-unknown-linux-gnueabihf-gcc \
  -DCMAKE_CXX_COMPILER=/Volumes/Extra/.konan/dependencies/armv7-unknown-linux-gnueabihf/bin/armv7-unknown-linux-gnueabihf-g++ \
  -DCMAKE_C_FLAGS="${CFLAGS}" \
  -DCMAKE_CXX_FLAGS="${CXXFLAGS}" \
  -DCMAKE_EXE_LINKER_FLAGS="${LDFLAGS}"  \
    && make -j 32 -C F2CLIBS \
    && make -j 32 -C BLAS \
    && make -j 32 -C SRC \
    && find . -name "*.a" -exec cp {} ../../OpenBLAS/install/lib \;


```

# 修改后才能执行,否则会报错
make -j32 openfst cub
./extras/install_openblas_clapack.sh


# 添加一个假的环境变量,欺骗这个垃圾脚本, 让他以为在linux系统上面跑
mkdir fakebin
echo -e '#!/bin/sh\necho Linux' > fakebin/uname
chmod +x fakebin/uname
export PATH="$(pwd)/fakebin:$PATH"
# 配置
cd ../src

# 打包静态库
```bash
从1330行修改. 垃圾脚本漏了静态库配置,直接判断的是动态库
    fi

    if $static_math; then
          echo "Configuring static OpenBlas since --static-math=yes"
    else
          echo "Configuring dynamically loaded OpenBlas since --static-math=no (the default)"
          if [ -f $OPENBLASROOT/lib/libopenblas.so ]; then
             OPENBLASLIBDIR=$OPENBLASROOT/lib
           elif [ -f $OPENBLASROOT/lib64/libopenblas.so ]; then
             # in REDHAT/CentOS package installs, the library is located here
             OPENBLASLIBDIR=$OPENBLASROOT/lib64
           else
             failure "Expected to find the file $OPENBLASROOT/lib/libopenblas.so"
          fi
    fi
```

./configure --host=armv7-linux-gnueabihf  --static  \
--mathlib=OPENBLAS_CLAPACK  \
--fst-root=/Users/vickyleu/Downloads/kaldi_root/kaldi/tools/openfst \
--use-cuda=no   --with-cudadecoder=no --use-cuda=no --debug-level=1

## --openblas-root=/Users/vickyleu/Downloads/kaldi_root/kaldi/tools/OpenBLAS/install \

# 打包动态库
./configure --shared --mathlib=OPENBLAS_CLAPACK  --fst-root=/Users/vickyleu/Downloads/kaldi_root/kaldi/tools/openfst/build \
        --host=armv7-linux-gnueabihf --static-fst=no 

# 编译
make -j32 online2 lm rnnlm

# 还原环境变量
export PATH=$(echo $PATH | sed -e "s|$(pwd)/fakebin:||")
cd ../..
git clone https://github.com/alphacep/vosk-api --depth=1
cd vosk-api/src
Makefile 需要改成静态库
```bash
# Locations of the dependencies
KALDI_ROOT?=$(HOME)/travis/kaldi
OPENFST_ROOT?=$(KALDI_ROOT)/tools/openfst
OPENBLAS_ROOT?=$(KALDI_ROOT)/tools/OpenBLAS/install
MKL_ROOT?=/opt/intel/mkl
CUDA_ROOT?=/usr/local/cuda
USE_SHARED?=0
# Math libraries
HAVE_OPENBLAS_CLAPACK?=1
HAVE_MKL?=0
HAVE_ACCELERATE=0
HAVE_CUDA?=0
# Compiler
CXX?=g++
AR?=ar
STRIP?=strip
EXT?=so
# Extra
EXTRA_CFLAGS?=
EXTRA_LDFLAGS?=
OUTDIR?=.

VOSK_SOURCES= \
	recognizer.cc \
	language_model.cc \
	model.cc \
	spk_model.cc \
	vosk_api.cc \
	postprocessor.cc

VOSK_HEADERS= \
	recognizer.h \
	language_model.h \
	model.h \
	spk_model.h \
	vosk_api.h \
        postprocessor.h
# -g 为调试符号
CFLAGS= -g -O3 -std=c++17 -Wno-deprecated-declarations -fPIC -DFST_NO_DYNAMIC_LINKING -I. -I$(KALDI_ROOT)/src -I$(OPENFST_ROOT)/include $(EXTRA_CFLAGS)

OBJECTS = $(patsubst %.cc,$(OUTDIR)/%.o,$(VOSK_SOURCES))


LDFLAGS=

ifeq ($(USE_SHARED), 0)
    LIBS = \
        $(KALDI_ROOT)/src/chain/kaldi-chain.a \
        $(KALDI_ROOT)/src/nnet2/kaldi-nnet2.a \
        $(KALDI_ROOT)/src/online2/kaldi-online2.a \
        $(KALDI_ROOT)/src/decoder/kaldi-decoder.a \
        $(KALDI_ROOT)/src/ivector/kaldi-ivector.a \
        $(KALDI_ROOT)/src/gmm/kaldi-gmm.a \
        $(KALDI_ROOT)/src/tree/kaldi-tree.a \
        $(KALDI_ROOT)/src/feat/kaldi-feat.a \
        $(KALDI_ROOT)/src/lat/kaldi-lat.a \
        $(KALDI_ROOT)/src/lm/kaldi-lm.a \
        $(KALDI_ROOT)/src/rnnlm/kaldi-rnnlm.a \
        $(KALDI_ROOT)/src/hmm/kaldi-hmm.a \
        $(KALDI_ROOT)/src/nnet3/kaldi-nnet3.a \
        $(KALDI_ROOT)/src/transform/kaldi-transform.a \
        $(KALDI_ROOT)/src/cudamatrix/kaldi-cudamatrix.a \
        $(KALDI_ROOT)/src/matrix/kaldi-matrix.a \
        $(KALDI_ROOT)/src/fstext/kaldi-fstext.a \
        $(KALDI_ROOT)/src/util/kaldi-util.a \
        $(KALDI_ROOT)/src/base/kaldi-base.a \
        $(OPENFST_ROOT)/lib/libfst.a \
        $(OPENFST_ROOT)/lib/libfstngram.a
else
    LDFLAGS += \
        -L$(KALDI_ROOT)/libs \
        -lkaldi-online2 -lkaldi-decoder -lkaldi-ivector -lkaldi-gmm -lkaldi-tree \
        -lkaldi-feat -lkaldi-lat -lkaldi-lm -lkaldi-rnnlm -lkaldi-hmm -lkaldi-nnet3 \
        -lkaldi-transform -lkaldi-cudamatrix -lkaldi-matrix -lkaldi-fstext \
        -lkaldi-util -lkaldi-base -lfst -lfstngram
endif

ifeq ($(HAVE_OPENBLAS_CLAPACK), 1)
    CFLAGS += -I$(OPENBLAS_ROOT)/include
    ifeq ($(USE_SHARED), 0)
        LIBS += \
            $(OPENBLAS_ROOT)/lib/libopenblas.a \
            $(OPENBLAS_ROOT)/lib/liblapack.a \
            $(OPENBLAS_ROOT)/lib/libblas.a \
            $(OPENBLAS_ROOT)/lib/libf2c.a
    else
        LDFLAGS += -lopenblas -llapack -lblas -lf2c
    endif
endif

ifeq ($(HAVE_MKL), 1)
    CFLAGS += -DHAVE_MKL=1 -I$(MKL_ROOT)/include
    LDFLAGS += -L$(MKL_ROOT)/lib/intel64 -Wl,-rpath=$(MKL_ROOT)/lib/intel64 -lmkl_rt -lmkl_intel_lp64 -lmkl_core -lmkl_sequential
endif

ifeq ($(HAVE_ACCELERATE), 1)
    LDFLAGS += -framework Accelerate
endif

ifeq ($(HAVE_CUDA), 1)
    VOSK_SOURCES += batch_recognizer.cc batch_model.cc
    VOSK_HEADERS += batch_recognizer.h batch_model.h

    CFLAGS+=-DHAVE_CUDA=1 -I$(CUDA_ROOT)/include

    LIBS := \
        $(KALDI_ROOT)/src/cudadecoder/kaldi-cudadecoder.a \
        $(KALDI_ROOT)/src/cudafeat/kaldi-cudafeat.a \
        $(LIBS)

    LDFLAGS += -L$(CUDA_ROOT)/lib64 -lcuda -lcublas -lcusparse -lcudart -lcurand -lcufft -lcusolver -lnvToolsExt
endif

LDFLAGS +=  -Wl,--strip-debug

# 添加ARMv7架构指定
CFLAGS += -march=armv7-a -mfpu=neon-vfpv4 -mfloat-abi=hard


# 默认目标
all: $(OUTDIR)/libvosk.$(EXT)

$(OUTDIR)/libvosk.$(EXT): $(OBJECTS)
	@find $(OUTDIR) -name '*.o' -exec $(OBJCOPY) --strip-symbol=lsame_ {} \;

	@echo "=> 解压必要的库..."
	@mkdir  -p  "$(OUTDIR)/libs"
	@cur_dir=`pwd` && \
	for lib in $(LIBS); do \
		if [ -f "$$lib" ]; then \
			cd "$$cur_dir" && \
			libname=`basename $$lib | sed 's/\.a$$//'` && \
			echo "处理库: $$libname (路径: $$lib)" && \
			mkdir -p "$(OUTDIR)/tmp/$$libname" && \
			cp "$$lib" "$(OUTDIR)/libs/" && \
			(cd "$(OUTDIR)/tmp/$$libname" && $(AR) x "$$lib" && \
			for f in *.o; do mv "$$f" "$${libname}_$$f"; done) ; \
		else \
			echo "错误: 找不到库文件 $$lib" && \
			exit 1 ; \
		fi ; \
	done

	@echo "=> 移除冲突符号..."
	@find "$(OUTDIR)/tmp/libblas" -name 'libblas_lsame*.o' -exec sh -c '$(OBJCOPY)  --strip-symbol=lsame_  "{}"' \;
	@find "$(OUTDIR)/tmp/libopenblas" -name '*.o' -exec sh -c '$(OBJCOPY)  --strip-symbol=lsame_  --strip-symbol=c_abs --strip-symbol=dcabs1_ --strip-symbol=scabs1_ --strip-symbol=z_abs "{}"' \;
	@find "$(OUTDIR)/tmp/libf2c" -name '*.o' -exec sh -c '$(OBJCOPY) --strip-symbol=main "{}"' \;


	@echo "=> 处理缺少的.note.GNU-stack段..."
	@echo "" | $(AS) -o temp_stack.o -c -   # 生成临时汇编目标文件
	@for obj in $$(find "$(OUTDIR)/tmp" -name "*.o"); do \
    if ! $(OBJDUMP) -h "$$obj" 2>/dev/null | grep -q '.note.GNU-stack'; then \
        $(OBJCOPY) --add-section .note.GNU-stack=temp_stack.o "$$obj"; \
    fi; \
	done
	@rm -f temp_stack.o

	@echo "=> 将所有处理好的.o文件移动到$(OUTDIR)/tmp"
	@find $(OUTDIR)/tmp -name '*.o' -exec mv {} $(OUTDIR)/tmp \;
	$(AR) rcs $@ $(OBJECTS)  $$(ls $(OUTDIR)/tmp/*.o 2>/dev/null)
	@echo "=> 处理库: libvosk.$(EXT) (路径: $@)"
	@$(CXX)  $(CFLAGS)  -static-libstdc++ -static-libgcc  -o $@ $^  -L$(KALDI_ROOT)/libs $(LDFLAGS) $(EXTRA_LDFLAGS)


	$(STRIP) --strip-debug $@
	@echo "处理完成. 请检查新库是否满足预期."
	@echo "构建成功! 输出文件: $@"
	@echo "优化后大小: $$(du -h $@ | cut -f1)"
	@echo "=> 清理临时文件..."
	@rm -rf $(OUTDIR)/tmp  $(OUTDIR)/*.o
	@mkdir -p $(OUTDIR)/tmp

#	@echo "=> 启动静态库极致瘦身流程"
#	# 备份原始文件
#	@cp $@ $@.bak 2>/dev/null || true
#	# 阶段1：解包并处理目标文件
#	@echo "解包并处理目标文件..."
#	@cd $(OUTDIR)/tmp && \
#	$(AR) x ../$(notdir $@) 2>/dev/null || true && \
#    find . -name '*.o' -exec $(OBJCOPY) \
#        --compress-debug-sections=zlib \
#        --strip-symbol=.L* \
#        --remove-section=.comment \
#        --remove-section=.note* {} \;
#
#	# 阶段2：瘦身后重新打包
#	@echo "重建Thin Archive..."
#	@$(AR) crs --thin $@.2 $(OUTDIR)/tmp/*.o
#
#	# 精准文件大小统计（兼容交叉编译环境）
#	@echo "优化后大小: $$(stat -f '%z' $@ | numfmt --to=iec --suffix=B --format='%.2f')"
#	@echo "优化后大小: $$(stat -f '%z' $@.2 | numfmt --to=iec --suffix=B --format='%.2f')"
#	@echo "处理完成. 请检查新库是否满足预期."
#	@echo "构建成功! 输出文件: $@"

	@rm -rf $(OUTDIR)/tmp

# 编译源文件为目标文件
$(OUTDIR)/%.o: %.cc $(VOSK_HEADERS)
	@mkdir -p $(OUTDIR)
	$(CXX) $(CFLAGS) -c -o $@  $<

clean:
	rm -f *.o *.so *.dll

```


KALDI_ROOT=/Users/vickyleu/Downloads/kaldi_root/kaldi \
OPENBLAS_ROOT=/Users/vickyleu/Downloads/kaldi_root/kaldi/tools/OpenBLAS/install \
OPENFST_ROOT=/Users/vickyleu/Downloads/kaldi_root/kaldi/tools/openfst  \
make -j 32 EXT=a  VERBOSE=1 HAVE_OPENBLAS_CLAPACK=1 BUILD_TYPE=Release EXT=a \
OUTDIR=build CXX=armv7-unknown-linux-gnueabihf-g++ \
EXTRA_CFLAGS=${CXXFLAGS}  \
EXTRA_LDFLAGS=${LDFLAGS} \
AR=armv7-unknown-linux-gnueabihf-ar STRIP=armv7-unknown-linux-gnueabihf-strip CC=armv7-unknown-linux-gnueabihf-gcc





find /Users/vickyleu/Downloads/kaldi_root/kaldi/src -name "*.a" -exec sh -c 'echo "检查文件: $1"; nm "$1" 2>/dev/null | grep --color=always -w "cblas_saxpy"' sh {} \;
